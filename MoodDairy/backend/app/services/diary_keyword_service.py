"""Offline Chinese diary keywords. Never calls a provider or downloads model weights."""
import os
import re
import threading
from collections import Counter
from functools import lru_cache
from pathlib import Path

# Small, original product vocabulary; exact source spans only, no inferred diagnoses.
EMOTIONS = {
    'joy': '开心 快乐 高兴 喜悦 幸福 满足 惊喜 兴奋 感恩 感谢 温暖 温柔',
    'calm': '平静 安心 安宁 放松 轻松 松弛 踏实 舒服 舒适 松了一口气',
    'hope': '期待 希望 向往 憧憬',
    'sadness': '难过 沮丧 低落 悲伤 失落 遗憾 孤独 委屈 迷茫 想念',
    'anxiety': '焦虑 忐忑 紧张 担心 不安 害怕 烦躁 压力',
    'fatigue': '疲惫 疲倦 劳累 累',
    'anger': '生气 愤怒 恼火 烦',
}
LABELS = {'joy': '喜悦', 'calm': '安宁', 'hope': '期待', 'sadness': '低落',
          'anxiety': '紧张', 'fatigue': '疲惫', 'anger': '烦闷'}
CATEGORY = {word: category for category, words in EMOTIONS.items() for word in words.split()}
PATTERN = re.compile('|'.join(sorted(map(re.escape, CATEGORY), key=len, reverse=True)))
NEGATION = re.compile(r'(?:没有|并不|不是|不再|不太|不怎么|不|没)(?:很|那么|特别|太)?$')
STOP = set('今天 昨天 明天 现在 时候 自己 觉得 感觉 然后 一个 一些 这样 那样 这里 那里 真的 有点 一下 事情 日记 标题 无标题 新日记 没有 可以 还是 已经 开始'.split())
_lock = threading.Lock()


def extract_basic_review(source):
    """Always-available exact-source phrases; no model, tokenizer, network, or invented mood."""
    text = '\n'.join([source['body'], *source['text_blocks'], *source['transcripts']])[:24000]
    words = {}
    for match in PATTERN.finditer(text):
        prefix = text[max(0, match.start() - 8):match.start()]
        negation = NEGATION.search(prefix)
        word = (negation.group() if negation else '') + match.group()
        words[word] = {'text': word, 'weight': .82,
                       'category': 'neutral' if negation else CATEGORY[match.group()], 'kind': 'emotion'}
    candidates = []
    for span in re.findall(r'[\u3400-\u9fffA-Za-z0-9]+', text):
        for phrase in re.split(r'然后|但是|因为|所以|今天|昨天|自己|觉得|感觉|一个|一些|没有|已经|正在|的|了|和|也|在|我|是|很|又|就|都|着|与', span):
            candidates.extend(phrase[i:i + 6] for i in range(0, len(phrase), 6))
    for word, count in Counter(candidates).most_common():
        if len(words) >= 12:
            break
        if word and word not in STOP and not any(word in other or other in word for other in words):
            words[word] = {'text': word, 'weight': min(.7, .35 + count * .04), 'category': 'neutral', 'kind': 'keyword'}
    if not words and text.strip():
        word = text.strip()[:8]
        words[word] = {'text': word, 'weight': .5, 'category': 'neutral', 'kind': 'keyword'}
    selected = sorted(words.values(), key=lambda word: -word['weight'])[:12]
    emotional = [word for word in selected if word['kind'] == 'emotion' and word['category'] != 'neutral']
    return {'body': ('原文留下了' + '、'.join('“' + w['text'] + '”' for w in emotional[:3]) + '这些感受。'
                     if emotional else '已从原文整理关键词；文字没有明确的情绪表达，不额外推断心情。'),
            'primary_emotion': '、'.join(dict.fromkeys(LABELS[w['category']] for w in emotional)) or '情绪不明确',
            'keywords': selected, 'extraction_method': 'source-phrases+lexicon'}


@lru_cache(maxsize=1)
def _extractor():
    import jieba
    from jieba.analyse import TextRank, TFIDF
    import jieba.posseg
    tagger = jieba.posseg.POSTokenizer(jieba.Tokenizer())
    allowed = {'n', 'nr', 'ns', 'nt', 'nz', 'vn', 'v', 'a'}
    def tokenize(text):
        return [word for word, flag in tagger.cut(text)
                if flag in allowed and 2 <= len(word) <= 8 and word not in STOP]
    return TextRank(), TFIDF(), tokenize


def _model_path():
    if os.environ.get('DIARY_KEYWORD_ENGINE') == 'textrank':
        return None
    configured = os.environ.get('DIARY_KEYBERT_MODEL_PATH')
    path = Path(configured) if configured else Path(__file__).resolve().parents[2] / '.local/models/bge-small-zh-v1.5'
    if configured and not path.is_dir():
        raise RuntimeError('DIARY_KEYBERT_MODEL_PATH must be an existing local model directory')
    return str(path) if path.is_dir() else None


@lru_cache(maxsize=1)
def _keybert(path):
    # BGE CLS pooling, local files only. No remote code, provider, or hub downloads.
    os.environ.setdefault('USE_TF', '0')
    from keybert import KeyBERT
    from keybert.backend import BaseEmbedder
    from transformers import AutoTokenizer, AutoModel
    import numpy as np
    import torch
    tokenizer = AutoTokenizer.from_pretrained(path, local_files_only=True, trust_remote_code=False)
    model = AutoModel.from_pretrained(path, local_files_only=True, trust_remote_code=False).eval()

    class LocalBge(BaseEmbedder):
        def embed(self, documents, verbose=False):
            # Average all diary chunks so later paragraphs influence keyword weights too.
            chunks, owners = [], []
            for index, document in enumerate(documents):
                for start in range(0, max(1, len(document)), 400):
                    chunks.append(document[start:start + 400]); owners.append(index)
            vectors = []
            with torch.inference_mode():
                for start in range(0, len(chunks), 16):
                    inputs = tokenizer(chunks[start:start + 16], padding=True, truncation=True,
                                       max_length=512, return_tensors='pt')
                    batch = model(**inputs).last_hidden_state[:, 0]
                    vectors.extend(torch.nn.functional.normalize(batch, p=2, dim=1).cpu().numpy())
            output = np.zeros((len(documents), model.config.hidden_size), dtype=np.float32)
            for index, vector in zip(owners, vectors):
                output[index] += vector
            return output / np.maximum(np.linalg.norm(output, axis=1, keepdims=True), 1e-12)

    return KeyBERT(model=LocalBge())


def extract_review(source):
    parts = [source['body'], *source['text_blocks'], *source['transcripts']]
    if source['title'] not in ('', '无标题', '无标题日记', '新日记'):
        parts.insert(0, source['title'])
    text = '\n'.join(parts)
    emotion_counts, categories = Counter(), {}
    for match in PATTERN.finditer(text):
        start, end = match.span()
        prefix = text[max(0, start - 8):start]
        negation = NEGATION.search(prefix)
        word, category = match.group(), CATEGORY[match.group()]
        if negation:
            word = text[start - len(negation.group()):end]
            start -= len(negation.group())
            category = 'neutral'
        emotion_counts[word] += 1
        categories[word] = category

    model_path = _model_path()
    # Serialize tokenizer/model initialization and inference; run outside the event loop.
    with _lock:
        ranker, tfidf, tokenizer = _extractor()
        if model_path:
            from sklearn.feature_extraction.text import CountVectorizer
            vectorizer = CountVectorizer(tokenizer=tokenizer, token_pattern=None, lowercase=False,
                                         ngram_range=(1, 1))
            candidates = _keybert(model_path).extract_keywords(text, vectorizer=vectorizer, top_n=36)
        else:
            candidates = ranker.textrank(text, topK=36, withWeight=True,
                                        allowPOS=('n', 'nr', 'ns', 'nt', 'nz', 'vn', 'v', 'a'))
            if len(candidates) < 8:
                candidates += tfidf.extract_tags(text, topK=24, withWeight=True,
                                                allowPOS=('n', 'nr', 'ns', 'nt', 'nz', 'vn', 'v', 'a'))
    words = []
    for word, count in emotion_counts.most_common(8):
        words.append({'text': word, 'weight': round(min(1., .72 + .09 * (count - 1)), 3),
                      'category': categories[word], 'kind': 'emotion'})
    seen = {w['text'] for w in words}
    for word, score in candidates:
        word = word.strip()
        if not (2 <= len(word) <= 8) or word in STOP or word in seen or word not in text:
            continue
        # Don't show a positive word cut from a negated emotion phrase.
        if any(word in emotion for emotion in emotion_counts):
            continue
        seen.add(word)
        words.append({'text': word, 'weight': round(max(.18, min(.85, float(score))), 3),
                      'category': 'neutral', 'kind': 'keyword'})
        if len(words) >= 12:
            break
    words.sort(key=lambda w: (-w['weight'], w['text']))
    emotional = [w for w in words if w['kind'] == 'emotion' and w['category'] != 'neutral']
    labels = list(dict.fromkeys(LABELS[w['category']] for w in emotional))[:3]
    primary = '、'.join(labels) if labels else '情绪不明确'
    body = ('原文留下了' + '、'.join('“' + w['text'] + '”' for w in emotional[:3]) + '这些感受。词语大小表示在这篇记录中的相对重要程度。'
            if emotional else '这篇记录没有明确的情绪表达，先把文字中的关键词留下，不替你判断心情。')
    return {'body': body, 'primary_emotion': primary, 'keywords': words,
            'extraction_method': 'keybert-local+lexicon' if model_path else 'textrank+lexicon'}
