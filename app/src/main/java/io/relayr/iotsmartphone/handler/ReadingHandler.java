package io.relayr.iotsmartphone.handler;

import android.location.Location;
import android.util.Log;

import com.crashlytics.android.Crashlytics;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import de.greenrobot.event.EventBus;
import io.relayr.android.RelayrSdk;
import io.relayr.iotsmartphone.IotApplication;
import io.relayr.iotsmartphone.storage.Constants;
import io.relayr.iotsmartphone.storage.Storage;
import io.relayr.java.helper.observer.ErrorObserver;
import io.relayr.java.helper.observer.SimpleObserver;
import io.relayr.java.model.AccelGyroscope;
import io.relayr.java.model.action.Reading;
import io.relayr.java.model.models.DeviceModel;
import io.relayr.java.model.models.error.DeviceModelsException;
import io.relayr.java.model.models.transport.Transport;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import rx.subjects.ReplaySubject;

import static io.relayr.iotsmartphone.storage.Constants.DeviceType.PHONE;
import static io.relayr.iotsmartphone.storage.Constants.DeviceType.WATCH;

public class ReadingHandler {

    private static final String TAG = "ReadingHandler";

    private static float sWatchData;
    private static float sPhoneData;
    private static long sTimestamp;
    public static float sWatchSpeed;
    public static float sPhoneSpeed;

    public static final Map<String, LimitedQueue<Reading>> readingsPhone;
    public static final Map<String, LimitedQueue<Reading>> readingsWatch;

    static {
        readingsPhone = new HashMap<>();
        readingsWatch = new HashMap<>();
        initializeReadings();
    }

    public static void initializeReadings() {
        readingsPhone.clear();
        readingsPhone.put("acceleration", new LimitedQueue<Reading>(Constants.defaultSizes.get("acceleration")));
        readingsPhone.put("angularSpeed", new LimitedQueue<Reading>(Constants.defaultSizes.get("angularSpeed")));
        readingsPhone.put("luminosity", new LimitedQueue<Reading>(Constants.defaultSizes.get("luminosity")));
        readingsPhone.put("batteryLevel", new LimitedQueue<Reading>(Constants.defaultSizes.get("batteryLevel")));
        readingsPhone.put("touch", new LimitedQueue<Reading>(Constants.defaultSizes.get("touch")));
        readingsPhone.put("rssi", new LimitedQueue<Reading>(Constants.defaultSizes.get("rssi")));
        readingsPhone.put("location", new LimitedQueue<Reading>(Constants.defaultSizes.get("location")));

        readingsWatch.clear();
        readingsWatch.put("acceleration", new LimitedQueue<Reading>(Constants.defaultSizes.get("acceleration")));
        readingsWatch.put("luminosity", new LimitedQueue<Reading>(Constants.defaultSizes.get("luminosity")));
        readingsWatch.put("batteryLevel", new LimitedQueue<Reading>(Constants.defaultSizes.get("batteryLevel")));
        readingsWatch.put("touch", new LimitedQueue<Reading>(Constants.defaultSizes.get("touch")));
    }

    public static Map<String, LimitedQueue<Reading>> readings(Constants.DeviceType mType) {
        return mType == PHONE ? readingsPhone : readingsWatch;
    }

    public static boolean isComplex(String meaning) {
        return meaning.equals("acceleration") || meaning.equals("angularSpeed") || meaning.equals("luminosity");
    }

    public static Observable<Boolean> getReadings() {
        if (Storage.instance().loadReadings(PHONE).isEmpty())
            return ReplaySubject.create(new Observable.OnSubscribe<Boolean>() {
                @Override public void call(final Subscriber<? super Boolean> subscriber) {
                    RelayrSdk.getDeviceModelsApi()
                            .getDeviceModelById(Storage.MODEL_PHONE)
                            .timeout(5, TimeUnit.SECONDS)
                            .flatMap(new Func1<DeviceModel, Observable<DeviceModel>>() {
                                @Override
                                public Observable<DeviceModel> call(DeviceModel deviceModel) {
                                    try {
                                        final Transport transport = deviceModel.getFirmwareByVersion("1.0.0").getDefaultTransport();
                                        Storage.instance().savePhoneReadings(transport.getReadings());
                                        Storage.instance().savePhoneCommands(transport.getCommands());
                                    } catch (DeviceModelsException e) {
                                        e.printStackTrace();
                                    }

                                    return RelayrSdk.getDeviceModelsApi().getDeviceModelById(Storage.MODEL_WATCH);
                                }
                            })
                            .timeout(5, TimeUnit.SECONDS)
                            .flatMap(new Func1<DeviceModel, Observable<DeviceModel>>() {
                                @Override
                                public Observable<DeviceModel> call(DeviceModel deviceModel) {
                                    try {
                                        final Transport transport = deviceModel.getLatestFirmware().getDefaultTransport();
                                        Storage.instance().saveWatchReadings(transport.getReadings());
                                    } catch (DeviceModelsException e) {
                                        e.printStackTrace();
                                    }

                                    return Observable.just(deviceModel);
                                }
                            })
                            .subscribeOn(Schedulers.io())
                            .subscribe(new SimpleObserver<DeviceModel>() {
                                @Override public void error(Throwable e) {
                                    Crashlytics.log(Log.ERROR, "ReadingsHandler", "Loading models error.");
                                    subscriber.onNext(false);
                                }

                                @Override public void success(DeviceModel o) {
                                    EventBus.getDefault().post(new Constants.DeviceModelEvent());
                                    subscriber.onNext(true);
                                }
                            });
                }
            });
        else return Observable.just(true);
    }

    public static Reading createAccelReading(float x, float y, float z) {
        return new Reading(0, System.currentTimeMillis(), "acceleration", "/", new AccelGyroscope.Acceleration(x, y, z));
    }

    public static Reading createGyroReading(float x, float y, float z) {
        return new Reading(0, System.currentTimeMillis(), "angularSpeed", "/", new AccelGyroscope.AngularSpeed(x, y, z));
    }

    public static void publish(Reading reading) {
        ReadingHandler.readingsPhone.get(reading.meaning).add(reading);
        if (IotApplication.isVisible(PHONE))
            EventBus.getDefault().post(new Constants.ReadingRefresh(PHONE, reading.meaning));
        if (Storage.ACTIVITY_PHONE.get(reading.meaning)) {
            if (Storage.instance().getDeviceId(PHONE) == null) return;
            sPhoneData += new Gson().toJson(reading).getBytes().length + 100;
            RelayrSdk.getWebSocketClient()
                    .publish(Storage.instance().getDeviceId(PHONE), reading)
                    .subscribeOn(Schedulers.io())
                    .subscribe(new ErrorObserver<Boolean>() {
                        @Override public void error(Throwable e) {
                            Crashlytics.log(Log.ERROR, TAG, "publish phone reading - error");
                            e.printStackTrace();
                        }
                    });
        }
    }

    public static void publishWatch(DataItem dataItem) {
        final String path = dataItem.getUri().getPath();
        final DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
        if (Constants.DEVICE_INFO_PATH.equals(path)) {
            Storage.instance().saveWatchData(dataMap.getString(Constants.DEVICE_MANUFACTURER),
                    dataMap.getString(Constants.DEVICE_MODEL), dataMap.getInt(Constants.DEVICE_SDK));
        } else if (Constants.SENSOR_ACCEL_PATH.equals(path)) {
            final float[] array = dataMap.getFloatArray(Constants.SENSOR_ACCEL);
            publishWatch(createAccelReading(array[0], array[1], array[2]));
        } else if (Constants.SENSOR_BATTERY_PATH.equals(path)) {
            String batteryData = dataMap.getString(Constants.SENSOR_BATTERY);
            final long ts = Long.parseLong(batteryData.split("#")[0]);
            final float val = Float.parseFloat(batteryData.split("#")[1]);
            publishWatch(new Reading(0, ts, "batteryLevel", "/", val));
        } else if (Constants.SENSOR_LIGHT_PATH.equals(path)) {
            float val = dataMap.getFloat(Constants.SENSOR_LIGHT);
            publishWatch(new Reading(0, System.currentTimeMillis(), "luminosity", "/", val));
        } else if (Constants.SENSOR_TOUCH_PATH.equals(path)) {
            String touchData = dataMap.getString(Constants.SENSOR_TOUCH);
            final long ts = Long.parseLong(touchData.split("#")[0]);
            final boolean val = Boolean.parseBoolean(touchData.split("#")[1]);
            publishWatch(new Reading(0, ts, "touch", "/", val));
        }
    }

    private static void publishWatch(Reading reading) {
        sWatchData += new Gson().toJson(reading.value).getBytes().length;
        ReadingHandler.readingsWatch.get(reading.meaning).add(reading);
        if (IotApplication.isVisible(WATCH))
            EventBus.getDefault().post(new Constants.ReadingRefresh(WATCH, reading.meaning));
        if (Storage.ACTIVITY_WATCH.get(reading.meaning)) {
            if (Storage.instance().getDeviceId(WATCH) == null) return;
            RelayrSdk.getWebSocketClient()
                    .publish(Storage.instance().getDeviceId(WATCH), reading)
                    .subscribeOn(Schedulers.io())
                    .subscribe(new ErrorObserver<Boolean>() {
                        @Override public void error(Throwable e) {
                            Crashlytics.log(Log.ERROR, TAG, "publish watch reading - error");
                            e.printStackTrace();
                        }
                    });
        }
    }

    public static void publishLocation(Location loc) {
        final LocationReading reading = new LocationReading(loc.getLatitude(), loc.getLongitude(), loc.getAltitude());
        publish(new Reading(0, System.currentTimeMillis(), "location", "/", reading));
    }

    public static void calculateSpeeds() {
        final long now = System.currentTimeMillis();
        if (sTimestamp <= 0) {
            sTimestamp = now;
            return;
        }
        final float seconds = (now - sTimestamp) / 1000f;
        sWatchSpeed = sWatchData / seconds;
        sPhoneSpeed = sPhoneData / seconds;

        sWatchData = 0;
        sPhoneData = 0;
        sTimestamp = now;
    }

    public static void publishTouch(boolean active) {
        ReadingHandler.publish(new Reading(0, System.currentTimeMillis(), "touch", "/", active));
    }

    public static void clearAfterLogOut() {
        sWatchData = 0;
        sPhoneData = 0;
        sTimestamp = 0;
        sWatchSpeed = 0;
        sPhoneSpeed = 0;
    }

    public static class LocationReading {
        private double latitude;
        private double longitude;
        private double altitude;

        public LocationReading(double latitude, double longitude, double altitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
        }

        public double latitude() {
            return latitude;
        }

        public double longitude() {
            return longitude;
        }

        public double altitude() {
            return altitude;
        }
    }
}
