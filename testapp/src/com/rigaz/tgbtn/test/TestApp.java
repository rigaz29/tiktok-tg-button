package com.rigaz.tgbtn.test;

import android.app.Application;

/** Stands in for AwemeHostApplication: the hook is the first line of onCreate. */
public class TestApp extends Application {
    @Override
    public void onCreate() {
        com.rigaz.tgbtn.Ttg.init(this);
        super.onCreate();
    }
}
