package com.statsig.androidLocalEvalSDK;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import com.google.common.math.Stats;
import com.statsig.androidLocalEvalSDK.*;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

public class StatsigFromJavaTest {
    private Application app;
    private StatsigClient client;
    private StatsigUser user;
    @Before
    public void setup() {
        TestUtil.Companion.mockDispatchers();

        app = TestUtil.Companion.getMockApp();
        TestUtil.Companion.stubAppFunctions(app);
        TestUtil.Companion.mockStatsigUtils();
        StatsigNetwork network = TestUtil.Companion.mockNetwork();
        user = new StatsigUser("testUser");
        TestUtil.Companion.startStatsigAndWait(app, network);
        client = Statsig.INSTANCE.getClient();
    }

    @Test
    public void testCheckGate() {
        boolean gate1 = client.checkGate(user, "always_on_gate");
        assert(gate1);
        boolean gate2 = client.checkGate(user, "on_for_statsig_email");
        assert(!gate2);
    }

    @Test
    public void testGetConfig() {
        DynamicConfig config1 = client.getConfig(user, "test_config");
        assert(config1.getBoolean("boolean", false));
        assert(config1.getInt("number", 0) == 4);
        assert(config1.getString("string", "a").equals("default"));
    }

    @Test
    public void testGetExperiment() {
        DynamicConfig experiment = client.getExperiment(user, "sample_experiment");
        assert(experiment.getBoolean("layer_param", false));
        assert(experiment.getBoolean("second_layer_param", false));
        assertEquals("test", experiment.getString("experiment_param", "Default"));
        assertEquals("test", experiment.getGroupName());
    }

    @Test
    public void testGetLayer() {
        Layer layer = client.getLayer(user, "a_layer");
        assertEquals("test",layer.getString("experiment_param", "Default"));
        assertEquals(true, layer.getBoolean("layer_param", false));
    }
}
