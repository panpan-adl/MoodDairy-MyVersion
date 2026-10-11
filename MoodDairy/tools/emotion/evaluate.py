"""Compare pinned pretrained models on LOCAL, labeled, already-cropped face images.

CSV: path,label,person_id (paths relative to CSV). Use held-out people/sessions;
do not tune on this CSV. --smoke checks inference only, never reports accuracy.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys
import time

HERE = Path(__file__).resolve().parent
if (HERE / '.runtime').exists():
    sys.path.insert(0, str(HERE / '.runtime'))
import numpy as np
import onnxruntime as ort
from PIL import Image

LABELS = ['angry', 'disgust', 'fear', 'happy', 'neutral', 'sad', 'surprise']


class Model:
    def __init__(self, path, labels, size, checksum):
        if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
            raise ValueError(f'Model checksum mismatch: {path}')
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])
        self.name = self.session.get_inputs()[0].name
        self.labels, self.size = labels, size
        assert self.session.get_inputs()[0].shape[-3:] == [3, size, size]
        assert self.session.get_outputs()[0].shape[-1] == len(labels)

    def predict(self, image, flip=False):
        pixels = np.asarray(image.convert('RGB').resize((self.size, self.size), Image.Resampling.BILINEAR), dtype=np.float32) / 255
        tensor = ((pixels - np.array([.485, .456, .406], dtype=np.float32)) /
                  np.array([.229, .224, .225], dtype=np.float32)).transpose(2, 0, 1)[None]
        def infer(x):
            logits = self.session.run(None, {self.name: np.ascontiguousarray(x)})[0][0]
            if not np.isfinite(logits).all():
                raise ValueError('Non-finite model output')
            exp = np.exp(logits - logits.max())
            return exp / exp.sum()
        start = time.perf_counter()
        scores = infer(tensor)
        if flip:
            scores = (scores + infer(tensor[:, :, :, ::-1])) / 2
        return self.labels[int(scores.argmax())], scores, (time.perf_counter() - start) * 1000


def metrics(rows):
    labels = LABELS + ['contempt']
    cm = {a: {b: 0 for b in labels} for a in labels}
    for r in rows:
        cm[r['label']][r['prediction']] += 1
    per_class = {}
    for a in LABELS:
        tp = cm[a][a]
        support = sum(cm[a].values())
        predicted = sum(cm[b][a] for b in labels)
        recall = tp / support if support else None
        precision = tp / predicted if predicted else 0
        f1 = 2 * precision * recall / (precision + recall) if recall is not None and precision + recall else 0
        per_class[a] = dict(support=support, recall=recall, precision=precision, f1=f1)
    supported = [v for v in per_class.values() if v['support']]
    return dict(n=len(rows), accuracy=sum(r['prediction'] == r['label'] for r in rows) / len(rows),
                macro_f1=sum(v['f1'] for v in supported) / len(supported), per_class=per_class,
                confusion_matrix=cm, median_ms=float(np.median([r['ms'] for r in rows])),
                p95_ms=float(np.percentile([r['ms'] for r in rows], 95)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path)
    parser.add_argument('--baseline-model', type=Path)
    parser.add_argument('--smoke', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.smoke == bool(args.dataset):
        parser.error('Select exactly one of --smoke or --dataset')
    app = HERE.parents[1]
    baseline = json.loads((app / 'app/src/main/assets/emotion/model-info.json').read_text())
    candidate = json.loads((HERE / 'candidate-info.json').read_text())
    saved_baseline = HERE / 'runs/ferplus-head-v1/baseline.onnx'
    original = args.baseline_model or (saved_baseline if saved_baseline.exists() else app / 'app/src/main/assets/emotion/enet_b2_7.onnx')
    b2 = Model(original, LABELS, 260, baseline.get('parent_model_sha256', baseline['sha256']))
    afew = Model(HERE / 'candidates/enet_b0_8_best_afew.onnx', candidate['labels'], 224, candidate['sha256'])
    models = [('b2_baseline', b2, False), ('b2_flip', b2, True), ('afew_candidate', afew, False)]
    if 'parent_model_sha256' in baseline:
        retrained = Model(app / 'app/src/main/assets/emotion/enet_b2_7.onnx', LABELS, 260, baseline['sha256'])
        models.insert(2, ('b2_retrained_flip', retrained, True))
    if args.smoke:
        rng = np.random.default_rng(42)
        image = Image.fromarray(rng.integers(0, 256, size=(280, 280, 3), dtype=np.uint8))
        result = {'kind': 'synthetic_inference_smoke_not_accuracy', 'runtime': ort.__version__, 'models': {}}
        for name, model, flip in models:
            model.predict(image, flip)  # warm-up
            observations = [model.predict(image, flip) for _ in range(5)]
            assert all(np.isfinite(x[1]).all() and abs(float(x[1].sum()) - 1) < 1e-5 for x in observations)
            result['models'][name] = {'valid_output': True, 'median_ms': float(np.median([x[2] for x in observations]))}
    else:
        with args.dataset.open(encoding='utf-8-sig', newline='') as f:
            manifest = list(csv.DictReader(f))
        if not manifest or any(r.get('label') not in LABELS or not r.get('path') or not r.get('person_id') for r in manifest):
            raise ValueError('CSV must contain path,label,person_id; use all seven labels for complete evaluation')
        result = {'kind': 'labeled_cropped_faces', 'dataset_sha256': hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
                  'notes': 'Static model comparison only; does not measure Android alignment or temporal pipeline.', 'models': {}}
        for name, model, flip in models:
            rows = []
            for row in manifest:
                with Image.open(args.dataset.parent / row['path']) as image:
                    pred, scores, ms = model.predict(image, flip)
                rows.append(dict(label=row['label'], prediction=pred, ms=ms, confidence=float(scores.max())))
            result['models'][name] = metrics(rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
