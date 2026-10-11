"""Real frozen-backbone transfer learning: train the B2 classifier on FER+ votes.

No torchvision/pickle checkpoints are needed. The original backbone stays frozen;
trainable Gemm weights are exported back into the original mobile ONNX graph.
The private test split is evaluated only after selecting the head on validation.
"""
from __future__ import annotations
import argparse
import copy
import csv
import hashlib
import io
import json
from pathlib import Path
import sys
import time
import zipfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / '.runtime'))
if (HERE / '.dml-runtime').exists():
    sys.path.insert(0, str(HERE / '.dml-runtime'))
import numpy as np
import onnx
from onnx import numpy_helper, helper, TensorProto
import onnxruntime as ort
from PIL import Image
import torch
from torch import nn

LABELS = ['angry', 'disgust', 'fear', 'happy', 'neutral', 'sad', 'surprise']
VOTE_COLUMNS = ['anger', 'disgust', 'fear', 'happiness', 'neutral', 'sadness', 'surprise']
BASE_SHA = '7863032d8c0c0bc259aa0dd7b8ebf6b607af454e7a0ea82be2b37767914ecd0f'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def log(message):
    print(message, flush=True)


def load_data(zip_path, vote_path):
    with zipfile.ZipFile(zip_path) as archive:
        matches = [n for n in archive.namelist() if Path(n).name == 'fer2013.csv']
        if len(matches) != 1:
            raise ValueError('Expected one original fer2013.csv')
        with archive.open(matches[0]) as f:
            images = list(csv.DictReader(io.TextIOWrapper(f, encoding='utf-8-sig')))
    with vote_path.open(encoding='utf-8-sig', newline='') as f:
        annotations = list(csv.DictReader(f))
    if len(images) != len(annotations):
        raise ValueError('FER+ row alignment mismatch')
    splits = {s: [] for s in ['Training', 'PublicTest', 'PrivateTest']}
    seen = set()
    removed = {'ambiguous_or_outside_7_classes': 0, 'exact_duplicates': 0}
    for row, votes in zip(images, annotations):
        if row['Usage'] != votes['Usage']:
            raise ValueError('FER+ split alignment mismatch')
        target = np.array([float(votes[c]) for c in VOTE_COLUMNS], dtype=np.float32)
        excluded = [float(votes[c]) for c in ['contempt', 'unknown', 'NF']]
        total = float(target.sum()) + sum(excluded)
        if total <= 0 or target.max() <= max(excluded) or target.max() / total < .5:
            removed['ambiguous_or_outside_7_classes'] += 1
            continue
        pixels = np.fromstring(row['pixels'], sep=' ', dtype=np.int32)
        if pixels.size != 2304 or pixels.min() < 0 or pixels.max() > 255:
            raise ValueError('Invalid FER image pixels')
        pixels = pixels.astype(np.uint8).reshape(48, 48)
        digest = hashlib.sha256(pixels.tobytes()).hexdigest()
        if digest in seen:
            removed['exact_duplicates'] += 1
            continue
        seen.add(digest)
        target /= target.sum()
        splits[row['Usage']].append((pixels, target))
    packed = {}
    for split, rows in splits.items():
        if not rows:
            raise ValueError(f'Empty split: {split}')
        pixels, targets = zip(*rows)
        targets = np.stack(targets)
        if set(targets.argmax(1)) != set(range(7)):
            raise ValueError(f'Missing expression classes in {split}')
        packed[split] = (np.stack(pixels), targets)
    return packed, removed


def preprocess(images):
    arrays = [np.asarray(Image.fromarray(p).convert('RGB').resize((260, 260),
              Image.Resampling.BILINEAR), dtype=np.float32) / 255 for p in images]
    x = np.stack(arrays)
    x = (x - np.array([.485, .456, .406], dtype=np.float32)) / np.array([.229, .224, .225], dtype=np.float32)
    return np.ascontiguousarray(x.transpose(0, 3, 1, 2))


def feature_session(model, batch, provider):
    graph = copy.deepcopy(model)
    last = graph.graph.node[-1]
    if last.op_type != 'Gemm' or list(last.input[1:]) != ['classifier.weight', 'classifier.bias']:
        raise ValueError('Unexpected pretrained classification head')
    feature_name = last.input[0]
    graph.graph.node.remove(last)
    del graph.graph.output[:]
    graph.graph.output.append(helper.make_tensor_value_info(feature_name, TensorProto.FLOAT, [batch, 1408]))
    graph.graph.input[0].type.tensor_type.shape.dim[0].dim_value = batch
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    options.enable_mem_pattern = False
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(graph.SerializeToString(), options, providers=[provider] if provider == 'CPUExecutionProvider' else [provider, 'CPUExecutionProvider'])
    if provider not in session.get_providers():
        raise RuntimeError(f'Requested provider unavailable: {provider}')
    return session


def extract(session, pixels, batch, output_path):
    if output_path.exists():
        saved = np.load(output_path, allow_pickle=False)
        if saved.shape == (len(pixels), 2, 1408) and np.isfinite(saved).all():
            log(f'Using cached features: {output_path.name}')
            return saved
    features = np.empty((len(pixels), 2, 1408), dtype=np.float32)
    name = session.get_inputs()[0].name
    started = time.perf_counter()
    last_progress = 0.0
    for start in range(0, len(pixels), batch):
        actual = pixels[start:start + batch]
        x = preprocess(actual)
        if len(actual) < batch:
            x = np.concatenate([x, np.repeat(x[-1:], batch - len(actual), axis=0)])
        features[start:start + len(actual), 0] = session.run(None, {name: x})[0][:len(actual)]
        features[start:start + len(actual), 1] = session.run(None, {name: np.ascontiguousarray(x[:, :, :, ::-1])})[0][:len(actual)]
        elapsed = time.perf_counter() - started
        if elapsed - last_progress >= 15 or start + batch >= len(pixels):
            log(f'{output_path.stem}: {min(start + batch, len(pixels))}/{len(pixels)} faces, {elapsed:.1f}s')
            last_progress = elapsed
    if not np.isfinite(features).all():
        raise ValueError('Invalid extracted features')
    output_path.parent.mkdir(parents=True, exist_ok=True)
    np.save(output_path, features, allow_pickle=False)
    return features


def probabilities(x):
    e = np.exp(x - x.max(-1, keepdims=True))
    return e / e.sum(-1, keepdims=True)


def evaluate(features, targets, weight, bias, temperature=1.0):
    scores = probabilities((features @ weight.T + bias) / temperature).mean(1)
    labels = targets.argmax(1)
    predictions = scores.argmax(1)
    cm = np.zeros((7, 7), dtype=np.int64)
    np.add.at(cm, (labels, predictions), 1)
    per_class = {}
    for i, label in enumerate(LABELS):
        support, predicted, tp = int(cm[i].sum()), int(cm[:, i].sum()), int(cm[i, i])
        recall, precision = tp / support if support else 0, tp / predicted if predicted else 0
        f1 = 2 * recall * precision / (recall + precision) if recall + precision else 0
        per_class[label] = dict(support=support, recall=recall, precision=precision, f1=f1)
    non_negative = np.isin(labels, [3, 4, 6])
    negative = np.isin(predictions, [0, 1, 2, 5])
    neutral = labels == 4
    return dict(n=len(labels), accuracy=float((labels == predictions).mean()),
                macro_f1=float(np.mean([v['f1'] for v in per_class.values()])), per_class=per_class,
                neutral_to_negative_rate=float(negative[neutral].mean()),
                nonnegative_to_negative_rate=float(negative[non_negative].mean()),
                soft_label_nll=float(-(targets * np.log(np.clip(scores, 1e-8, 1))).sum(1).mean()),
                confusion_matrix=cm.tolist())


def passes(candidate, baseline, recall_delta=0, fpr_tolerance=.01):
    return (candidate['macro_f1'] > baseline['macro_f1'] and
            all(candidate['per_class'][c]['recall'] >= baseline['per_class'][c]['recall'] + recall_delta for c in ['sad', 'angry']) and
            candidate['neutral_to_negative_rate'] <= baseline['neutral_to_negative_rate'] + fpr_tolerance and
            candidate['nonnegative_to_negative_rate'] <= baseline['nonnegative_to_negative_rate'] + fpr_tolerance)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=HERE / 'runs/ferplus-head-v1')
    parser.add_argument('--base-model', type=Path, help='Original baseline ONNX; use the saved baseline after deploying a trained model')
    parser.add_argument('--epochs', type=int, default=30)
    parser.add_argument('--batch', type=int, default=16)
    parser.add_argument('--provider', choices=['DmlExecutionProvider', 'CPUExecutionProvider'], default='DmlExecutionProvider')
    args = parser.parse_args()
    np.random.seed(20261010)
    torch.manual_seed(20261010)
    torch.set_num_threads(4)
    args.output.mkdir(parents=True, exist_ok=True)
    base_path = args.base_model or HERE.parents[1] / 'app/src/main/assets/emotion/enet_b2_7.onnx'
    if sha(base_path) != BASE_SHA:
        raise ValueError('Expected original verified B2 model; restore baseline before rerunning')
    zip_path, vote_path = HERE / 'datasets/fer2013-original.zip', HERE / 'datasets/fer2013new.csv'
    dataset, removed = load_data(zip_path, vote_path)
    log('Split sizes: ' + str({k: len(v[0]) for k, v in dataset.items()}) + '; removed: ' + str(removed))
    model = onnx.load(base_path)
    tensors = {t.name: numpy_helper.to_array(t).copy() for t in model.graph.initializer if t.name.startswith('classifier.')}
    base_w, base_b = tensors['classifier.weight'], tensors['classifier.bias']
    session = feature_session(model, args.batch, args.provider)
    cache_key = hashlib.sha256((BASE_SHA + sha(zip_path) + sha(vote_path) + 'ferplus-majority-v1-rgb260-bilinear').encode()).hexdigest()[:16]
    features = {}
    for split, (pixels, _) in dataset.items():
        features[split] = extract(session, pixels, args.batch, HERE / 'cache' / cache_key / f'{split}.npy')
    del session
    base_val = evaluate(features['PublicTest'], dataset['PublicTest'][1], base_w, base_b)
    log('Baseline validation: ' + json.dumps(base_val))
    head = nn.Linear(1408, 7)
    with torch.no_grad():
        head.weight.copy_(torch.from_numpy(base_w))
        head.bias.copy_(torch.from_numpy(base_b))
    optimizer = torch.optim.AdamW(head.parameters(), lr=2e-4, weight_decay=1e-3)
    train_x = torch.from_numpy(features['Training'])
    train_y = torch.from_numpy(dataset['Training'][1])
    original_w, original_b = torch.from_numpy(base_w), torch.from_numpy(base_b)
    counts = train_y.sum(0)
    class_weight = (counts.mean() / counts).sqrt().clamp(.6, 1.8)
    class_weight[[0, 5]] *= 1.15
    best = None
    history = []
    for epoch in range(args.epochs):
        permutation = torch.randperm(len(train_x))
        losses = []
        for start in range(0, len(train_x), 256):
            indices = permutation[start:start + 256]
            views = torch.randint(0, 2, (len(indices),))
            x, y = train_x[indices, views], train_y[indices]
            logits = head(x)
            supervised = -(y * class_weight * logits.log_softmax(1)).sum(1).mean()
            with torch.no_grad():
                teacher = (x @ original_w.T + original_b).softmax(1)
            distillation = -(teacher * logits.log_softmax(1)).sum(1).mean()
            anchor = (head.weight - original_w).square().mean() + (head.bias - original_b).square().mean()
            loss = supervised + .15 * distillation + .5 * anchor
            optimizer.zero_grad(); loss.backward()
            torch.nn.utils.clip_grad_norm_(head.parameters(), 5.0)
            optimizer.step()
            losses.append(float(loss.detach()))
        w, b = head.weight.detach().numpy().copy(), head.bias.detach().numpy().copy()
        val = evaluate(features['PublicTest'], dataset['PublicTest'][1], w, b)
        acceptable = passes(val, base_val, recall_delta=0, fpr_tolerance=.01)
        row = dict(epoch=epoch + 1, loss=float(np.mean(losses)), validation=val, acceptable=acceptable)
        history.append(row)
        if acceptable and (best is None or val['macro_f1'] > best[2]['macro_f1']):
            best = (w, b, val, epoch + 1)
        log(f'Epoch {epoch+1}: loss={row["loss"]:.4f} macro_f1={val["macro_f1"]:.4f} sad={val["per_class"]["sad"]["recall"]:.4f} angry={val["per_class"]["angry"]["recall"]:.4f} neutral_to_negative={val["neutral_to_negative_rate"]:.4f} acceptable={acceptable}')
    # Even a rejected training run is preserved, but never silently replaces the app's model.
    selected = best if best is not None else (w, b, val, args.epochs)
    w, b, val, epoch = selected
    temperatures = [.8, 1., 1.2, 1.4, 1.6, 1.8, 2.]
    temperature = min(temperatures, key=lambda t: evaluate(features['PublicTest'], dataset['PublicTest'][1], w, b, t)['soft_label_nll'])
    w, b = w / temperature, b / temperature
    candidate_val = evaluate(features['PublicTest'], dataset['PublicTest'][1], w, b)
    baseline_test = evaluate(features['PrivateTest'], dataset['PrivateTest'][1], base_w, base_b)
    candidate_test = evaluate(features['PrivateTest'], dataset['PrivateTest'][1], w, b)
    eligible = best is not None and passes(candidate_val, base_val) and passes(candidate_test, baseline_test, recall_delta=.01)
    report = dict(training='frozen EfficientNet-B2 backbone; genuinely optimized 7-class linear classifier',
                  trained_parameters=int(w.size + b.size), seed=20261010, selected_epoch=epoch, temperature=temperature,
                  source_model_sha256=BASE_SHA, dataset_sha256=sha(zip_path), annotations_sha256=sha(vote_path),
                  data_sources=['https://www.kaggle.com/datasets/deadskull7/fer2013', 'https://github.com/microsoft/FERPlus'],
                  split_sizes={k: len(v[0]) for k, v in dataset.items()}, removed=removed,
                  labels=LABELS, validation_baseline=base_val, validation_retrained=candidate_val,
                  test_baseline=baseline_test, test_retrained=candidate_test, eligible_for_public_dataset_trial=eligible,
                  limits='FER+ is static 48x48 grayscale data; private split has no person IDs. This does not establish local RGB camera accuracy or temporal suggestion accuracy.',
                  selection='Epoch and temperature chosen only on PublicTest. PrivateTest evaluated once after selection. Deployment requires macro F1 gain, sad/angry recall gain >=1 percentage point and nonnegative-to-negative false-alarm increase <=1 percentage point.',
                  history=history)
    for tensor in model.graph.initializer:
        if tensor.name == 'classifier.weight': tensor.CopyFrom(numpy_helper.from_array(w.astype(np.float32), tensor.name))
        if tensor.name == 'classifier.bias': tensor.CopyFrom(numpy_helper.from_array(b.astype(np.float32), tensor.name))
    onnx.checker.check_model(model)
    output = args.output / 'enet_b2_7_ferplus.onnx'
    onnx.save(model, output)
    # Verify the serialized graph against the learned head using the CPU runtime used on mobile.
    options = ort.SessionOptions(); options.intra_op_num_threads = 2; options.log_severity_level = 3
    exported = ort.InferenceSession(str(output), options, providers=['CPUExecutionProvider'])
    baseline_session = ort.InferenceSession(str(base_path), options, providers=['CPUExecutionProvider'])
    feature_cpu = feature_session(onnx.load(base_path), 1, 'CPUExecutionProvider')
    sample = preprocess(dataset['PrivateTest'][0][:1])
    feature = feature_cpu.run(None, {feature_cpu.get_inputs()[0].name: sample})[0]
    expected = feature @ w.T + b
    actual = exported.run(None, {exported.get_inputs()[0].name: sample})[0]
    np.testing.assert_allclose(actual, expected, atol=1e-4, rtol=1e-4)
    baseline_actual = baseline_session.run(None, {baseline_session.get_inputs()[0].name: sample})[0]
    np.testing.assert_allclose(baseline_actual, feature @ base_w.T + base_b, atol=1e-4, rtol=1e-4)
    # Confirm the accelerated feature cache agrees with CPU features within numeric tolerance.
    np.testing.assert_allclose(features['PrivateTest'][0, 0], feature[0], atol=.01, rtol=.01)
    report['model_sha256'] = sha(output)
    report['export_cpu_parity_verified'] = True
    (args.output / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    (args.output / 'baseline.onnx').write_bytes(base_path.read_bytes())
    log('FINAL ' + json.dumps({k: report[k] for k in ['selected_epoch', 'temperature', 'eligible_for_public_dataset_trial', 'model_sha256', 'test_baseline', 'test_retrained']}))


if __name__ == '__main__':
    main()
