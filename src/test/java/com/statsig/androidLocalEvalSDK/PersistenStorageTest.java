package com.statsig.androidLocalEvalSDK;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.lang3.NotImplementedException;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

public class PersistenStorageTest {
    private Application app;
    private StatsigClient client;
    private StatsigUser user;
    private PersistentTestStorage persistentStorage = new PersistentTestStorage();
    private StatsigOptions option = new StatsigOptions();
    private Map<String,String> testUserPersistedValue = new HashMap<>();
    private int deleteValueCount = 0;
    private int saveValueCount = 0;
    private CountDownLatch countDownLatch = new CountDownLatch(1);
    public class PersistentInitializedCallback implements IPersistentStorageCallback {

        @Override
        public void onLoaded(@NonNull Map<String, String> values) {
            testUserPersistedValue = values;
            countDownLatch.countDown();
        }
    }
    public class PersistentTestStorage implements UserPersistentStorageInterface {
        private Map<String, Map<String,String>> values = new HashMap<>();

        @Nullable
        @Override
        public Object load(@NonNull String key, @NonNull Continuation<? super Map<String,String>> $completion) {
            throw new NotImplementedException("");
        }

        @Override
        public void save(@NonNull String key, @NonNull String experimentName, @NonNull String data) {
            if(!values.containsKey(key)) {
                values.put(key, new HashMap<String, String>());
            }
            ++saveValueCount;
            values.get(key).put(experimentName, data);
        }

        @Override
        public void delete(@NonNull String key, @NonNull String experiment) {
            ++deleteValueCount;
            if(values.containsKey(key)) {
                values.get(key).remove(experiment);
            }
        }

        @Override
        public void loadAsync(@NonNull String key, @NonNull IPersistentStorageCallback callback) {
            if(values.containsKey(key)) {
                callback.onLoaded(values.get(key));
            }else {
                callback.onLoaded(new HashMap<String,String>());
            }
        }
    }

    @Before
    public void setup() {
        TestUtil.Companion.mockDispatchers();
        app = TestUtil.Companion.getMockApp();
        TestUtil.Companion.stubAppFunctions(app);
        TestUtil.Companion.mockStatsigUtils();
        StatsigNetwork network = TestUtil.Companion.mockNetwork();
        user = new StatsigUser("testUser");
        option.setUserPersistentStorage(persistentStorage);
        TestUtil.Companion.startStatsigAndWait(app, network, option);
        client = Statsig.INSTANCE.getClient();
    }

    @Test
    public void testGetRightStickyValue() throws InterruptedException {
        GetExperimentOptions option = new GetExperimentOptions(false, new HashMap<String, String>());
        DynamicConfig config = client.getExperiment(user, "sample_experiment", option);
        assert(this.saveValueCount == 1);
        assert(config.getEvaluationDetails().getReason() == EvaluationReason.NETWORK);
        persistentStorage.loadAsync("testUser:userid", new PersistentInitializedCallback());
        countDownLatch.await();
        option.setUserPersistedValues(testUserPersistedValue);
        config = client.getExperiment(user, "sample_experiment", option);
        assert(config.getEvaluationDetails().getReason() == EvaluationReason.PERSISTED);

    }

}
