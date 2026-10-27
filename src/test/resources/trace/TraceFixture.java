class TraceFixture {
    static int checks;
    int field = 7;

    static int selected(boolean flag) {
        int value = 1;
        value = value + 2;
        if (check(flag)) {
            value = 4;
        } else {
            value = 5;
        }
        return value;
    }

    static boolean check(boolean flag) { checks++; return flag; }
    static int untouched() { int other = 9; return other; }
}
