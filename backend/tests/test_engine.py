from app.rules import engine as E


def d(domain, **kw):
    return E.DomainInput(domain, **kw)


def test_green_when_all_fine():
    out = E.evaluate_domain(d("D2", readings={"muac_cm": 13.5, "hb_gdl": 12.0}), 27)
    assert out.color == E.GREEN


def test_missing_required_is_incomplete_never_green():
    out = E.evaluate_domain(d("D2", readings={"hb_gdl": 12.0}), 27)
    assert out.color == E.INCOMPLETE


def test_muac_red_dedupes_yellow():
    out = E.evaluate_domain(d("D2", readings={"muac_cm": 11.0}), 27)
    assert out.color == E.RED
    assert [e.rule_id for e in out.evidence] == ["R-D2-001"]


def test_hb_98_is_yellow_anaemia():
    out = E.evaluate_domain(d("D2", readings={"muac_cm": 13.0, "hb_gdl": 9.8}), 27)
    assert out.color == E.YELLOW and out.evidence[0].focus == "anaemia"


def test_name_call_and_speech_red_with_cross_note():
    out = E.evaluate_domain(d("D4", tasks={"name_call_trials": 2, "name_call_turns": 0, "words_named": 2}), 27)
    assert out.color == E.RED
    ids = {e.rule_id for e in out.evidence}
    assert {"R-D4-001", "R-D4-002"} <= ids and "R-D4-003" not in ids
    assert E.cross_domain_notes([out])[0]["id"] == "X-001"


def test_worried_raises_to_yellow():
    out = E.evaluate_domain(d("D3", worried=True, worried_reason="सुस्त"), 27)
    assert out.color == E.YELLOW


def test_ai_can_only_raise_and_needs_evidence_ids():
    no_ids = E.evaluate_domain(d("D3", ai_findings=[{"present": True, "color": "RED", "model": "m"}]), 27)
    assert no_ids.color == E.YELLOW
    absent = E.evaluate_domain(d("D3", ai_findings=[{"present": False}]), 27)
    assert absent.color == E.GREEN


def test_combine_worst_wins():
    outs = [E.DomainOutcome("D1", E.GREEN, []), E.DomainOutcome("D2", E.YELLOW, []),
            E.DomainOutcome("D4", E.RED, [])]
    assert E.combine(outs) == E.RED
    assert E.combine([E.DomainOutcome("D1", E.GREEN, [])], visit_worried=True) == E.YELLOW


def test_unsure_on_red_flag_question_is_yellow():
    out = E.evaluate_domain(d("D1", answers={"q_d1_cleft": "unsure"}), 27)
    assert out.color == E.YELLOW


def test_danger_signs():
    assert E.danger_sign_emergency({"ds_fits": True})
    assert not E.danger_sign_emergency({"ds_fits": False})
