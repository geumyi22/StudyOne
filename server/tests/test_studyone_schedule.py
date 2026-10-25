"""Synthetic deterministic, fail-closed v4 learning schedule contract."""
import copy
import unittest

from server.studyone_study_schedule import preview
from server.studyone_sync import ApiProblem


def plan():
    return {
        "startDate": "2026-10-25",
        "endDate": "2026-11-02",
        "maxPerDay": 2,
        "firstReview": "학습지",
        "lanes": [
            {"label": "2단원", "items": [
                {"title": "2-1 소단원 평가", "total": 3},
                {"title": "2-2 소단원 평가", "total": 3},
                {"title": "2단원 대단원 평가", "total": 3}]},
            {"label": "3단원", "items": [
                {"title": "3-1 소단원 평가", "total": 3},
                {"title": "3-2 소단원 평가", "total": 3},
                {"title": "3단원 대단원 평가", "total": 3}]}
        ]
    }


class ScheduleTests(unittest.TestCase):
    def test_nine_days_eighteen_unique_papers_two_lanes(self):
        original = plan()
        before = copy.deepcopy(original)
        result = preview(original)
        self.assertEqual(original, before)
        self.assertEqual((result["totalPapers"], result["workDays"]), (18, 9))
        self.assertTrue(result["readOnly"])
        self.assertTrue(result["requiresConfirmation"])
        self.assertEqual(len(result["days"]), 9)
        self.assertEqual(result["days"][0]["date"], "2026-10-25")
        self.assertEqual(result["days"][-1]["date"], "2026-11-02")
        self.assertEqual(result["days"][0]["papers"], [
            {"lane": "2단원", "title": "2-1 소단원 평가 ①"},
            {"lane": "3단원", "title": "3-1 소단원 평가 ①"}])
        self.assertEqual([day["review"] for day in result["days"]],
                         ["학습지", "교재"] * 4 + ["학습지"])
        papers = [paper["title"] for day in result["days"] for paper in day["papers"]]
        self.assertEqual(len(set(papers)), 18)
        for day in result["days"]:
            self.assertEqual(len(day["papers"]), 2)
            self.assertEqual({p["lane"] for p in day["papers"]},
                             {"2단원", "3단원"})

    def test_resume_only_remaining_papers_and_shift_review(self):
        candidate = plan()
        candidate["startDate"] = "2026-10-26"
        candidate["lanes"][0]["items"][0]["completed"] = 1
        candidate["lanes"][1]["items"][0]["completed"] = 1
        result = preview(candidate)
        self.assertEqual(result["totalPapers"], 16)
        self.assertEqual(result["days"][0]["papers"][0]["title"],
                         "2-1 소단원 평가 ②")
        self.assertEqual(result["days"][0]["papers"][1]["title"],
                         "3-1 소단원 평가 ②")
        self.assertEqual(result["days"][-1]["date"], "2026-11-02")

    def test_single_skipped_day_and_alternate_study_days(self):
        candidate = plan()
        candidate["endDate"] = "2026-11-03"
        candidate["skipDates"] = ["2026-10-26"]
        result = preview(candidate)
        self.assertEqual(result["totalPapers"], 18)
        self.assertEqual(result["days"][1]["date"], "2026-10-27")
        self.assertEqual(result["days"][1]["review"], "교재")
        self.assertEqual(len(result["days"]), 9)

    def test_insufficient_capacity_is_rejected_entirely(self):
        candidate = plan()
        candidate["maxPerDay"] = 1
        with self.assertRaises(ApiProblem) as caught:
            preview(candidate)
        self.assertEqual((caught.exception.status, caught.exception.code),
                         (409, "SCHEDULE_OVER_CAPACITY"))

    def test_duplicates_or_invalid_dates_fail_closed(self):
        for invalid in [
            dict(plan(), startDate="2026-02-30"),
            dict(plan(), endDate="2026-10-24"),
            dict(plan(), firstReview="말하기"),
            dict(plan(), maxPerDay=True),
            dict(plan(), maxPerDay=0),
            dict(plan(), skipDates=["2026-10-26", "2026-10-26"]),
            dict(plan(), skipDates=["2026-11-04"]),
            dict(plan(), secretToken="should never accept secrets"),
        ]:
            with self.subTest(invalid=invalid), self.assertRaises(ApiProblem):
                preview(invalid)
        duplicate = plan()
        duplicate["lanes"][0]["items"].append(
            {"title": "2-1 소단원 평가", "total": 2})
        with self.assertRaises(ApiProblem) as caught:
            preview(duplicate)
        self.assertEqual(caught.exception.code, "DUPLICATE_SCHEDULE_ITEM")

    def test_all_completed_is_empty_and_does_not_make_new_tasks(self):
        candidate = plan()
        for lane in candidate["lanes"]:
            for item in lane["items"]:
                item["completed"] = item["total"]
        result = preview(candidate)
        self.assertEqual(result["totalPapers"], 0)
        self.assertEqual(result["days"], [])


if __name__ == "__main__":
    unittest.main()
