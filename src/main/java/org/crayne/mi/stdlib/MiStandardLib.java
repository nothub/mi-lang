package org.crayne.mi.stdlib;

import org.crayne.mi.lang.MiCallable;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class MiStandardLib {

    public static String standardLib() {
        return """
pub? true = 1b;
pub? false = 0b;

module std {

	pub nat fn println~ (string s) -> "$stdclass";
    pub nat fn print~ (string s) -> "$stdclass";
    pub nat fn println~ (int i) -> "$stdclass";
    pub nat fn print~ (int i) -> "$stdclass";
    pub nat fn println~ (double d) -> "$stdclass";
    pub nat fn print~ (double d) -> "$stdclass";
    pub nat fn println~ (float f) -> "$stdclass";
    pub nat fn print~ (float f) -> "$stdclass";
    pub nat fn println~ (long l) -> "$stdclass";
    pub nat fn print~ (long l) -> "$stdclass";
    pub nat fn println~ (bool b) -> "$stdclass";
    pub nat fn print~ (bool b) -> "$stdclass";
    pub nat fn println~ (char c) -> "$stdclass";
    pub nat fn print~ (char c) -> "$stdclass";
    pub nat fn sleep~ (long millis) -> "$stdclass";
    pub nat fn random_uuid_long :: long () -> "$stdclass";

}

module termion {

	pub nat fn color_fg :: string (int r, int g, int b) -> "$stdtermion";
	pub nat fn color_bg :: string (int r, int g, int b) -> "$stdtermion";
	
	pub nat fn color_fg :: string (string hex) -> "$stdtermion";
	pub nat fn color_bg :: string (string hex) -> "$stdtermion";
	
}

STANDARDLIB_MI_FINISH_CODE;"""
                .replace("$stdclass", MiStandardLib.class.getName())
                .replace("$stdtermion", StdTermion.class.getName());
    }

    @MiCallable
    public static void println(@NotNull final String str) {
        System.out.println(str);
    }

    @MiCallable
    public static void print(@NotNull final String str) {
        System.out.print(str);
    }

    @MiCallable
    public static void println(final Integer i) {
        System.out.println(i);
    }

    @MiCallable
    public static void print(final Integer i) {
        System.out.print(i);
    }

    @MiCallable
    public static void println(final Double d) {
        System.out.println(d);
    }

    @MiCallable
    public static void print(final Double d) {
        System.out.print(d);
    }

    @MiCallable
    public static void println(final Float f) {
        System.out.println(f);
    }

    @MiCallable
    public static void print(final Float f) {
        System.out.print(f);
    }

    @MiCallable
    public static void println(final Long l) {
        System.out.println(l);
    }

    @MiCallable
    public static void print(final Long l) {
        System.out.print(l);
    }

    @MiCallable
    public static void println(final Boolean b) {
        System.out.println(b);
    }

    @MiCallable
    public static void print(final Boolean b) {
        System.out.print(b);
    }

    @MiCallable
    public static void println(final Character c) {
        System.out.println(c);
    }

    @MiCallable
    public static void print(final Character c) {
        System.out.print(c);
    }

    @MiCallable
    public static void sleep(final Long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @MiCallable
    public static Long random_uuid_long() {
        return UUID.randomUUID().getMostSignificantBits();
    }

}