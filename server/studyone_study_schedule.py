"""Pure, permissionless study-plan preview; NEVER persists tasks or progress.

Model multiple study lanes (e.g. textbook units 2 and 3), round-robin each
lane's papers, cap daily work, and alternate the concept-review source.
Designed to be previewed before any user-approved StudyOne proposal.
"""
from __future__ import annotations

from datetime import date, timedelta
import re

try:
    from .studyone_sync import ApiProblem
except ImportError:
    from studyone_sync import ApiProblem

MAX_DAYS = 120
MAX_PAPERS = 240
CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"


def _date(value):
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        raise ApiProblem(400, "INVALID_SCHEDULE_DATE")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as exc:
        raise ApiProblem(400, "INVALID_SCHEDULE_DATE") from exc
    return parsed


def _label(value):
    if (not isinstance(value, str) or not 1 <= len(value) <= 80
            or value != value.strip() or not value.isprintable()):
        raise ApiProblem(400, "INVALID_SCHEDULE_LABEL")
    return value


def _number(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise ApiProblem(400, "INVALID_SCHEDULE_COUNT")
    return value


def _paper_name(title, number):
    mark = CIRCLED[number - 1] if number <= len(CIRCLED) else f"({number})"
    return f"{title} {mark}"


def preview(request):
    """Return a deterministic, read-only schedule or reject the whole request.

    Dates are date-only (not local device timestamps) to avoid UTC day shifts.
    Every lane receives fair turns; tasks are distributed without duplicating
    already-completed paper numbers. No user/account database is consulted.
    """
    if not isinstance(request, dict) or not (
        {"startDate", "endDate", "lanes"} <= set(request)
        <= {"startDate", "endDate", "lanes", "maxPerDay",
            "skipDates", "firstReview"}
    ):
        raise ApiProblem(400, "INVALID_SCHEDULE")
    first, last = _date(request["startDate"]), _date(request["endDate"])
    duration = (last - first).days
    if duration < 0 or duration >= MAX_DAYS:
        raise ApiProblem(400, "INVALID_SCHEDULE_RANGE")
    cap = _number(request.get("maxPerDay", 2), 1, 12)
    first_review = request.get("firstReview", "학습지")
    if first_review not in ("학습지", "교재"):
        raise ApiProblem(400, "INVALID_REVIEW_CYCLE")
    skips = request.get("skipDates", [])
    if not isinstance(skips, list) or len(skips) > MAX_DAYS:
        raise ApiProblem(400, "INVALID_SKIP_DATES")
    parsed_skips = {_date(s) for s in skips}
    if len(parsed_skips) != len(skips) or any(s < first or s > last for s in parsed_skips):
        raise ApiProblem(400, "INVALID_SKIP_DATES")
    raw_lanes = request["lanes"]
    if not isinstance(raw_lanes, list) or not 1 <= len(raw_lanes) <= 8:
        raise ApiProblem(400, "INVALID_SCHEDULE_LANES")
    lane_names, lanes = set(), []
    total = 0
    for lane in raw_lanes:
        if not isinstance(lane, dict) or set(lane) != {"label", "items"}:
            raise ApiProblem(400, "INVALID_SCHEDULE_LANES")
        name = _label(lane["label"])
        if name in lane_names:
            raise ApiProblem(409, "DUPLICATE_SCHEDULE_LANE")
        lane_names.add(name)
        entries = lane["items"]
        if not isinstance(entries, list) or not 1 <= len(entries) <= 20:
            raise ApiProblem(400, "INVALID_SCHEDULE_ITEMS")
        titles, parsed = set(), []
        for entry in entries:
            if (not isinstance(entry, dict) or
                    set(entry) not in ({"title", "total"}, {"title", "total", "completed"})):
                raise ApiProblem(400, "INVALID_SCHEDULE_ITEMS")
            title = _label(entry["title"])
            if title in titles:
                raise ApiProblem(409, "DUPLICATE_SCHEDULE_ITEM")
            titles.add(title)
            count = _number(entry["total"], 1, 60)
            completed = _number(entry.get("completed", 0), 0, count)
            remaining = count - completed
            total += remaining
            parsed.append({"title": title, "next": completed + 1, "remaining": remaining})
        lanes.append({"name": name, "papers": parsed, "cursor": 0})
    if total > MAX_PAPERS:
        raise ApiProblem(400, "SCHEDULE_TOO_LARGE")
    workdays = [first + timedelta(days=n) for n in range(duration + 1)
                if first + timedelta(days=n) not in parsed_skips]
    capacity = len(workdays) * cap
    if total > capacity:
        raise ApiProblem(409, "SCHEDULE_OVER_CAPACITY")

    days = []
    for day_index, day in enumerate(workdays):
        if total == 0:
            break
        scheduled = []
        for slot in range(cap):
            # An empty lane cannot monopolize a day; loop to next lane.
            wanted = (day_index * cap + slot) % len(lanes)
            candidate = None
            for advance in range(len(lanes)):
                index = (wanted + advance) % len(lanes)
                if any(p["remaining"] for p in lanes[index]["papers"]):
                    candidate = lanes[index]
                    break
            if candidate is None:
                break
            papers = candidate["papers"]
            for advance in range(len(papers)):
                index = (candidate["cursor"] + advance) % len(papers)
                item = papers[index]
                if item["remaining"]:
                    scheduled.append({"lane": candidate["name"],
                                      "title": _paper_name(item["title"], item["next"])})
                    item["next"] += 1
                    item["remaining"] -= 1
                    total -= 1
                    candidate["cursor"] = (index + 1) % len(papers)
                    break
        if scheduled:
            review = first_review if day_index % 2 == 0 else (
                "교재" if first_review == "학습지" else "학습지")
            days.append({"date": day.isoformat(), "review": review,
                         "papers": scheduled})
    assert total == 0  # capacity checked above; never return a partial preview.
    return {
        "schema": 1,
        "readOnly": True,
        "requiresConfirmation": True,
        "startDate": first.isoformat(),
        "endDate": last.isoformat(),
        "dailyCap": cap,
        "workDays": len(workdays),
        "totalPapers": sum(len(d["papers"]) for d in days),
        "days": days,
        "notice": ("계획 미리보기 전용입니다. 기존 숙제·진도는 변경되지 않습니다. "
                   "실제 저장은 최신 계정 revision 확인과 사용자 승인이 필요합니다.")
    }
