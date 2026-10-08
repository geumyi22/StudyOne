package com.studyone.app;

import java.time.LocalDate;

public final class Models {
    private Models() {}

    public static final class School {
        public final String eduCode, schoolCode, name, kind, address;
        public School(String eduCode, String schoolCode, String name, String kind, String address) {
            this.eduCode = eduCode;
            this.schoolCode = schoolCode;
            this.name = name;
            this.kind = kind;
            this.address = address;
        }
        public String identity() { return eduCode + ":" + schoolCode; }
    }

    public static final class TimetableEntry {
        public final LocalDate date;
        public final int period;
        public final String subject;
        public TimetableEntry(LocalDate date, int period, String subject) {
            this.date = date; this.period = period; this.subject = subject;
        }
    }

    public static final class Meal {
        public final LocalDate date;
        public final String menu, kcal;
        public Meal(LocalDate date, String menu, String kcal) {
            this.date = date; this.menu = menu; this.kcal = kcal;
        }
    }

    public static final class StudyTask {
        public final long id;
        public final String type, subject, title;
        public final LocalDate dueDate;
        public final int importance;
        public final boolean completed;
        public StudyTask(long id, String type, String subject, String title,
                         LocalDate dueDate, int importance, boolean completed) {
            this.id = id; this.type = type; this.subject = subject; this.title = title;
            this.dueDate = dueDate; this.importance = importance; this.completed = completed;
        }
        public StudyTask withCompleted(boolean value) {
            return new StudyTask(id, type, subject, title, dueDate, importance, value);
        }
    }
}
