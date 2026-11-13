#!/usr/bin/env python3
"""Execute the real Python/Java/Swift schedulers against one synthetic golden contract.
No app stores, external accounts or network calls are used. Requires Java 17 and Swift 5.9+.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from server.studyone_study_schedule import preview
from server.studyone_sync import ApiProblem


def fingerprint(value):
    text = f"{value['totalPapers']}|{value['workDays']}|{value['dailyCap']}\n"
    for day in value['days']:
        text += f"{day['date']}|{day['review']}\n"
        text += ''.join(p['lane'] + '\t' + p['title'] + '\n' for p in day['papers'])
    return hashlib.sha256(text.encode()).hexdigest()


def java_string(value):
    # JSON string escapes are also valid Java escapes for this controlled Unicode fixture.
    return json.dumps(value, ensure_ascii=True)


def java_driver(cases):
    calls = []
    for index, case in enumerate(cases):
        p = case['plan']
        lanes = []
        for lane in p['lanes']:
            papers = ','.join(f'new StudyPaperSchedule.Paper({java_string(x["title"])},{x["total"]},{x.get("completed",0)})' for x in lane['items'])
            lanes.append(f'new StudyPaperSchedule.Lane({java_string(lane["label"])},Arrays.asList({papers}))')
        skip = ','.join(f'LocalDate.parse({java_string(s)})' for s in p.get('skipDates', []))
        # Java's public API takes Set; reject duplicated serialized input before conversion.
        duplicate_skips = len(p.get('skipDates', [])) != len(set(p.get('skipDates', [])))
        expression = f'''StudyPaperSchedule.preview(LocalDate.parse({java_string(p['startDate'])}),
            LocalDate.parse({java_string(p['endDate'])}),{p['maxPerDay']},new HashSet<>(Arrays.asList({skip})),
            {java_string(p['firstReview'])},Arrays.asList({','.join(lanes)}))'''
        body = 'throw new IllegalArgumentException("duplicate serialized dates");' if duplicate_skips else f'System.out.println("{index}:"+hash({expression}));'
        calls.append(f'''try {{ {body} }} catch (IllegalArgumentException | java.time.DateTimeException error) {{ System.out.println("{index}:ERROR"); }}''')
    return '''import com.studyone.app.StudyPaperSchedule;
import java.time.LocalDate;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public class ScheduleGolden {
static String hash(StudyPaperSchedule.Preview v) throws Exception {
StringBuilder b=new StringBuilder().append(v.remainingPapers).append('|').append(v.workDays).append('|').append(v.dailyLimit).append('\\n');
for(var day:v.days){b.append(day.date).append('|').append(day.review).append('\\n');
for(var p:day.papers)b.append(p.lane).append('\\t').append(p.title).append('\\n');}
return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));}
public static void main(String[] args) throws Exception {''' + '\n'.join(calls) + '}}'


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True, timeout=180, **kwargs).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--skip-swift', action='store_true', help='Java + Python only; explicitly reports Swift not run')
    args = parser.parse_args()
    from schedule_cases import cases
    expected = [c['expected']['sha256'] if c['expected'] else 'ERROR' for c in cases]
    actual = []
    for case in cases:
        original = copy.deepcopy(case['plan'])
        try:
            value = preview(case['plan'])
            actual.append(fingerprint(value))
            papers = [p for d in value['days'] for p in d['papers']]
            assert len(papers) == value['totalPapers']
            assert all(len(d['papers']) <= value['dailyCap'] for d in value['days'])
            assert all(d['date'] not in original.get('skipDates', []) for d in value['days'])
        except ApiProblem:
            actual.append('ERROR')
        assert original == case['plan'], 'Scheduler changed its input'
    assert actual == expected, 'Python golden mismatch'
    with tempfile.TemporaryDirectory(prefix='studyone-schedule-') as folder:
        temp = Path(folder)
        fixture = temp/'fixture.json'
        fixture.write_text(json.dumps({'cases':cases},ensure_ascii=False),encoding='utf-8')
        (temp/'ScheduleGolden.java').write_text(java_driver(cases))
        run(['javac','-encoding','UTF-8','-d',str(temp), str(ROOT/'app/src/main/java/com/studyone/app/StudyPaperSchedule.java'),str(temp/'ScheduleGolden.java')])
        output = run(['java','-cp',str(temp),'ScheduleGolden']).splitlines()
        assert output == [f'{i}:{v}' for i,v in enumerate(expected)], 'Java golden mismatch: '+repr([(i,cases[i]['name']) for i,v in enumerate(output) if v!=f'{i}:{expected[i]}'])
        if not args.skip_swift:
            # The Swift driver prints base64 canonical text; hashing stays in Python to
            # avoid a CryptoKit dependency in the Linux test harness.
            run(['swiftc',str(ROOT/'ios/StudyOne/StudyPaperSchedule.swift'),str(ROOT/'scripts/tests/swift_schedule/main.swift'),'-o',str(temp/'swift-schedule')])
            import base64
            lines = run([str(temp/'swift-schedule'),str(fixture)]).splitlines()
            swift_actual = ['ERROR' if line=='ERROR' else hashlib.sha256(base64.b64decode(line,validate=True)).hexdigest() for line in lines]
            assert swift_actual == expected, 'Swift golden mismatch: '+repr([(i,cases[i]['name']) for i,v in enumerate(swift_actual) if v!=expected[i]])
    print(f'SCHEDULE_RUNTIME_PASSED: {len(cases)} fixtures each for Python, Java'+(', Swift' if not args.skip_swift else '; Swift NOT RUN'))


if __name__ == '__main__':
    main()
