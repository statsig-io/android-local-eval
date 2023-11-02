//package com.statsig.androidLocalEvalSDK;
//
//import android.app.Application;
//
//import org.junit.Before;
//import org.junit.jupiter.api.Test;
//
//import java.util.HashMap;
//import java.util.Map;
//
//class StatsigFromJava {
//    private Application app;
//    private StatsigClient client;
//    private StatsigUser user;
//    @Before
//    private void setup() {
//        TestUtil.Companion.mockDispatchers();
//
//        app = TestUtil.Companion.getMockApp();
//        TestUtil.Companion.stubAppFunctions(app);
//        TestUtil.Companion.mockStatsigUtils();
//        StatsigNetwork network = TestUtil.Companion.mockNetwork();
//        client = new StatsigClient();
//        client.statsigNetwork = network;
//        user = new StatsigUser("testUser");
//    }
//
//    @Test
//    public void testCheckGate() {
////        client.initialize(app, "client-key");
////        client.checkGate(user, "always_on_gate");
//    }
//
//    @Test
//    public void testGetConfig() {
//
//    }
//
//    @Test
//    public void testGetExperiment() {
//
//    }
//
//    @Test
//    public void testGetLayer() {
//
//    }
//}
