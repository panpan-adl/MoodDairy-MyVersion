import copy
import csv
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from retrain import LABELS, VOTE_COLUMNS, evaluate, load_data, passes, np


class TrainingDataTests(unittest.TestCase):
    def make_dataset(self, root, mismatch=False, duplicate=False, ambiguous=False):
        images = io.StringIO()
        annotations = io.StringIO()
        a = csv.DictWriter(images, fieldnames=['emotion', 'pixels', 'Usage'])
        b = csv.DictWriter(annotations, fieldnames=['Usage'] + VOTE_COLUMNS + ['contempt', 'unknown', 'NF'])
        a.writeheader(); b.writeheader()
        for split_index, split in enumerate(['Training', 'PublicTest', 'PrivateTest']):
            for label in range(7):
                a.writerow(dict(emotion=label, pixels=' '.join([str(split_index * 7 + label)] * 2304), Usage=split))
                votes = {k: 0 for k in b.fieldnames}
                votes['Usage'] = 'PrivateTest' if mismatch and split_index == 0 and label == 0 else split
                votes[VOTE_COLUMNS[label]] = 10
                b.writerow(votes)
        if duplicate or ambiguous:
            a.writerow(dict(emotion=0, pixels=' '.join(['0'] * 2304), Usage='Training'))
            votes = {k: 0 for k in b.fieldnames}; votes['Usage'] = 'Training'
            if ambiguous:
                votes.update(anger=4, sadness=3, neutral=3)
            else:
                votes['anger'] = 10
            b.writerow(votes)
        archive, vote_path = root / 'images.zip', root / 'votes.csv'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('fer2013.csv', images.getvalue())
        vote_path.write_text(annotations.getvalue())
        return archive, vote_path

    def test_mapping_matches_android_order_and_official_splits(self):
        with tempfile.TemporaryDirectory() as d:
            packed, _ = load_data(*self.make_dataset(Path(d)))
            self.assertEqual(LABELS, ['angry', 'disgust', 'fear', 'happy', 'neutral', 'sad', 'surprise'])
            for pixels, targets in packed.values():
                self.assertEqual(pixels.shape, (7, 48, 48))
                np.testing.assert_array_equal(targets, np.eye(7, dtype=np.float32))

    def test_duplicate_and_ambiguous_examples_are_excluded(self):
        for kind in ['duplicate', 'ambiguous']:
            with tempfile.TemporaryDirectory() as d:
                packed, removed = load_data(*self.make_dataset(Path(d), **{kind: True}))
                self.assertEqual(len(packed['Training'][0]), 7)
                self.assertEqual(sum(removed.values()), 1)

    def test_row_alignment_mismatch_is_fatal(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError, 'alignment'):
                load_data(*self.make_dataset(Path(d), mismatch=True))

    def test_accurate_overall_score_cannot_hide_sadness_regression(self):
        features = np.zeros((7, 2, 1408), dtype=np.float32)
        metrics = evaluate(features, np.eye(7, dtype=np.float32), np.zeros((7, 1408), dtype=np.float32), np.zeros(7, dtype=np.float32))
        candidate = copy.deepcopy(metrics)
        candidate['macro_f1'] += .1
        candidate['per_class']['sad']['recall'] = 0
        metrics['per_class']['sad']['recall'] = .5
        self.assertFalse(passes(candidate, metrics))

    def test_neutral_false_alarms_block_promotion(self):
        baseline = dict(macro_f1=.7, per_class={k: {'recall': .6} for k in LABELS},
                        neutral_to_negative_rate=.1, nonnegative_to_negative_rate=.1)
        candidate = copy.deepcopy(baseline)
        candidate['macro_f1'] = .8
        candidate['neutral_to_negative_rate'] = .2
        self.assertFalse(passes(candidate, baseline))


if __name__ == '__main__':
    unittest.main()
