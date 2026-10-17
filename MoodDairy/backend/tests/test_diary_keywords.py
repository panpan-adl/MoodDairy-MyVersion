import pytest
from app.services.diary_keyword_service import extract_review


@pytest.fixture(autouse=True)
def lightweight_engine(monkeypatch):
    monkeypatch.setenv("DIARY_KEYWORD_ENGINE", "textrank")


def review(text):
    return extract_review({'title': '', 'body': text, 'text_blocks': [], 'transcripts': []})


def test_words_are_grounded_unique_weighted_and_bounded():
    text = '今天散步时很开心，和朋友喝咖啡，感觉平静又温暖。回家完成了实验报告，有些疲惫但也很安心。'
    value = review(text)
    words = value['keywords']
    assert 1 <= len(words) <= 12
    assert len({w['text'] for w in words}) == len(words)
    assert all(w['text'] in text and 0 <= w['weight'] <= 1 for w in words)
    assert [w['weight'] for w in words] == sorted((w['weight'] for w in words), reverse=True)
    assert any(w['category'] == 'calm' for w in words)
    assert any(w['category'] == 'fatigue' for w in words)


@pytest.mark.parametrize('text,forbidden', [
    ('我不开心，也不怎么焦虑。', ['开心', '焦虑']),
    ('我没有很难过，只是不太紧张。', ['难过', '紧张']),
])
def test_negations_preserve_original_spans_without_wrong_coloring(text, forbidden):
    value = review(text)
    assert not any(w['text'] in forbidden for w in value['keywords'])
    assert all(w['category'] == 'neutral' for w in value['keywords'])
    assert value['primary_emotion'] == '情绪不明确'


def test_objective_schedule_does_not_invent_emotions():
    value = review('早上七点上课，中午去食堂吃饭，晚上整理实验报告。')
    assert value['primary_emotion'] == '情绪不明确'
    assert all(w['kind'] == 'keyword' for w in value['keywords'])


def test_very_short_emotion_still_becomes_a_word():
    assert review('累。')['keywords'][0]['text'] == '累'


def test_empty_or_symbols_do_not_get_example_words():
    assert review('')['keywords'] == []
    assert review('。。。😊')['keywords'] == []


def test_available_local_keybert_model_runs_without_network(monkeypatch):
    import requests
    from app.services.diary_keyword_service import _model_path
    monkeypatch.setenv('DIARY_KEYWORD_ENGINE', 'keybert')
    if not _model_path():
        pytest.skip('Local BGE weights are provisioned separately from the repository')
    def reject_network(*args, **kwargs):
        raise AssertionError('Local model tried to access the network')
    monkeypatch.setattr(requests.sessions.Session, 'request', reject_network)
    value = review('今天和朋友散步，喝咖啡，心里很开心。晚上写完实验报告，有些疲惫。')
    assert value['extraction_method'] == 'keybert-local+lexicon'
    assert any(w['kind'] == 'keyword' for w in value['keywords'])
    assert all(w['text'] not in ('有些', '一杯') for w in value['keywords'])
