package top.jessi.vlc_simple;

import android.app.Application;

/**
 * Created by Jessi on 2024/6/11 16:06
 * Email：17324719944@189.cn
 * Describe：
 */
public class MyApp extends Application {

    private static MyApp sMyApp;

    @Override
    public void onCreate() {
        super.onCreate();
        sMyApp = this;
    }

    public static MyApp getInstance() {
        return sMyApp;
    }

}
