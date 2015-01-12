package com.o3dr.android.client;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.o3dr.android.client.interfaces.DroneListener;
import com.o3dr.services.android.lib.coordinate.LatLong;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.connection.ConnectionResult;
import com.o3dr.services.android.lib.drone.mission.Mission;
import com.o3dr.services.android.lib.drone.mission.MissionItemType;
import com.o3dr.services.android.lib.drone.mission.item.MissionItem;
import com.o3dr.services.android.lib.drone.property.Altitude;
import com.o3dr.services.android.lib.drone.property.Attitude;
import com.o3dr.services.android.lib.drone.property.Battery;
import com.o3dr.services.android.lib.drone.property.CameraProxy;
import com.o3dr.services.android.lib.drone.property.Gps;
import com.o3dr.services.android.lib.drone.property.GuidedState;
import com.o3dr.services.android.lib.drone.property.Home;
import com.o3dr.services.android.lib.drone.property.Parameter;
import com.o3dr.services.android.lib.drone.property.Parameters;
import com.o3dr.services.android.lib.drone.property.Signal;
import com.o3dr.services.android.lib.drone.property.Speed;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.Type;
import com.o3dr.services.android.lib.drone.property.VehicleMode;
import com.o3dr.services.android.lib.gcs.follow.FollowState;
import com.o3dr.services.android.lib.gcs.follow.FollowType;
import com.o3dr.services.android.lib.mavlink.MavlinkMessageWrapper;
import com.o3dr.services.android.lib.model.IDroneApi;
import com.o3dr.services.android.lib.model.IObserver;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by fhuya on 11/4/14.
 */
public class Drone {

    private static final String CLAZZ_NAME = Drone.class.getName();
    private static final String TAG = Drone.class.getSimpleName();

    public interface OnAttributeRetrievedCallback<T extends Parcelable> {
        void onRetrievalSucceed(T attribute);

        void onRetrievalFailed();
    }

    public static class AttributeRetrievedListener<T extends Parcelable> implements OnAttributeRetrievedCallback<T> {

        @Override
        public void onRetrievalSucceed(T attribute) {
        }

        @Override
        public void onRetrievalFailed() {
        }
    }

    public interface OnMissionItemsBuiltCallback<T extends MissionItem> {
        void onMissionItemsBuilt(MissionItem.ComplexItem<T>[] complexItems);
    }

    public static final int COLLISION_SECONDS_BEFORE_COLLISION = 2;
    public static final double COLLISION_DANGEROUS_SPEED_METERS_PER_SECOND = -3.0;
    public static final double COLLISION_SAFE_ALTITUDE_METERS = 1.0;

    public static final String ACTION_GROUND_COLLISION_IMMINENT = CLAZZ_NAME + ".ACTION_GROUND_COLLISION_IMMINENT";
    public static final String EXTRA_IS_GROUND_COLLISION_IMMINENT = "extra_is_ground_collision_imminent";

    private final IBinder.DeathRecipient binderDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            notifyDroneServiceInterrupted("Lost access to the drone api.");
        }
    };

    private final ConcurrentLinkedQueue<DroneListener> droneListeners = new ConcurrentLinkedQueue<>();

    private final Handler handler;
    private final ServiceManager serviceMgr;
    private final DroneObserver droneObserver;
    private final DroneApiListener apiListener;

    private IDroneApi droneApi;
    private ConnectionParameter connectionParameter;
    private ExecutorService asyncScheduler;

    // flightTimer
    // ----------------
    private long startTime = 0;
    private long elapsedFlightTime = 0;

    public Drone(ServiceManager serviceManager, Handler handler) {
        this.handler = handler;
        this.serviceMgr = serviceManager;
        this.apiListener = new DroneApiListener(this);
        this.droneObserver = new DroneObserver(this);
    }

    public void start() {
        if (!serviceMgr.isServiceConnected())
            throw new IllegalStateException("Service manager must be connected.");

        if (isStarted())
            return;

        try {
            this.droneApi = serviceMgr.get3drServices().registerDroneApi(this.apiListener, serviceMgr.getApplicationId());
            this.droneApi.asBinder().linkToDeath(binderDeathRecipient, 0);
        } catch (RemoteException e) {
            throw new IllegalStateException("Unable to retrieve a valid drone handle.");
        }

        if (asyncScheduler == null || asyncScheduler.isShutdown())
            asyncScheduler = Executors.newFixedThreadPool(1);

        addAttributesObserver(this.droneObserver);
        resetFlightTimer();
    }

    public void destroy() {
        removeAttributesObserver(this.droneObserver);

        try {
            if (isStarted()) {
                this.droneApi.asBinder().unlinkToDeath(binderDeathRecipient, 0);
                serviceMgr.get3drServices().releaseDroneApi(this.droneApi);
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage(), e);
        }

        if (asyncScheduler != null) {
            asyncScheduler.shutdownNow();
            asyncScheduler = null;
        }

        this.droneApi = null;
        droneListeners.clear();
    }

    private void checkForGroundCollision() {
        Speed speed = getAttribute(AttributeType.SPEED);
        Altitude altitude = getAttribute(AttributeType.ALTITUDE);
        if (speed == null || altitude == null)
            return;

        double verticalSpeed = speed.getVerticalSpeed();
        double altitudeValue = altitude.getAltitude();

        boolean isCollisionImminent = altitudeValue
                + (verticalSpeed * COLLISION_SECONDS_BEFORE_COLLISION) < 0
                && verticalSpeed < COLLISION_DANGEROUS_SPEED_METERS_PER_SECOND
                && altitudeValue > COLLISION_SAFE_ALTITUDE_METERS;

        Bundle extrasBundle = new Bundle(1);
        extrasBundle.putBoolean(EXTRA_IS_GROUND_COLLISION_IMMINENT, isCollisionImminent);
        notifyAttributeUpdated(ACTION_GROUND_COLLISION_IMMINENT, extrasBundle);
    }

    private void handleRemoteException(RemoteException e) {
        if (droneApi != null && !droneApi.asBinder().pingBinder()) {
            final String errorMsg = e.getMessage();
            Log.e(TAG, errorMsg, e);
            notifyDroneServiceInterrupted(errorMsg);
        }
    }

    public double getSpeedParameter() {
        Parameters params = getAttribute(AttributeType.PARAMETERS);
        if (params != null) {
            Parameter speedParam = params.getParameter("WPNAV_SPEED");
            if (speedParam != null)
                return speedParam.getValue();
        }

        return 0;
    }

    public void resetFlightTimer() {
        elapsedFlightTime = 0;
        startTime = SystemClock.elapsedRealtime();
    }

    public void stopTimer() {
        // lets calc the final elapsed timer
        elapsedFlightTime += SystemClock.elapsedRealtime() - startTime;
        startTime = SystemClock.elapsedRealtime();
    }

    public long getFlightTime() {
        State droneState = getAttribute(AttributeType.STATE);
        if (droneState != null && droneState.isFlying()) {
            // calc delta time since last checked
            elapsedFlightTime += SystemClock.elapsedRealtime() - startTime;
            startTime = SystemClock.elapsedRealtime();
        }
        return elapsedFlightTime / 1000;
    }

    public <T extends Parcelable> T getAttribute(String type) {
        if (!isStarted() || type == null)
            return null;

        T attribute = null;
        Bundle carrier = null;
        try {
            carrier = droneApi.getAttribute(type);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }

        if (carrier != null) {
            ClassLoader classLoader = getAttributeClassLoader(type);
            if (classLoader != null) {
                carrier.setClassLoader(classLoader);
                attribute = carrier.getParcelable(type);
            }
        }

        return attribute == null ? this.<T>getAttributeDefaultValue(type) : attribute;
    }

    public <T extends Parcelable> void getAttributeAsync(final String attributeType,
                                                         final OnAttributeRetrievedCallback<T> callback) {
        if (callback == null)
            throw new IllegalArgumentException("Callback must be non-null.");

        if (!isStarted()) {
            callback.onRetrievalFailed();
            return;
        }

        asyncScheduler.execute(new Runnable() {
            @Override
            public void run() {
                final T attribute = getAttribute(attributeType);

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (attribute == null)
                            callback.onRetrievalFailed();
                        else
                            callback.onRetrievalSucceed(attribute);
                    }
                });
            }
        });
    }

    private <T extends Parcelable> T getAttributeDefaultValue(String attributeType) {
        switch (attributeType) {
            case AttributeType.ALTITUDE:
                return (T) new Altitude();

            case AttributeType.GPS:
                return (T) new Gps();

            case AttributeType.STATE:
                return (T) new State();

            case AttributeType.PARAMETERS:
                return (T) new Parameters();

            case AttributeType.SPEED:
                return (T) new Speed();

            case AttributeType.ATTITUDE:
                return (T) new Attitude();

            case AttributeType.HOME:
                return (T) new Home();

            case AttributeType.BATTERY:
                return (T) new Battery();

            case AttributeType.MISSION:
                return (T) new Mission();

            case AttributeType.SIGNAL:
                return (T) new Signal();

            case AttributeType.GUIDED_STATE:
                return (T) new GuidedState();

            case AttributeType.TYPE:
                return (T) new Type();

            case AttributeType.FOLLOW_STATE:
                return (T) new FollowState();

            case AttributeType.CAMERA:
            default:
                return null;
        }
    }

    private ClassLoader getAttributeClassLoader(String attributeType) {
        switch (attributeType) {
            case AttributeType.ALTITUDE:
                return Altitude.class.getClassLoader();

            case AttributeType.GPS:
                return Gps.class.getClassLoader();

            case AttributeType.STATE:
                return State.class.getClassLoader();

            case AttributeType.PARAMETERS:
                return Parameters.class.getClassLoader();

            case AttributeType.SPEED:
                return Speed.class.getClassLoader();

            case AttributeType.CAMERA:
                return CameraProxy.class.getClassLoader();

            case AttributeType.ATTITUDE:
                return Attitude.class.getClassLoader();

            case AttributeType.HOME:
                return Home.class.getClassLoader();

            case AttributeType.BATTERY:
                return Battery.class.getClassLoader();

            case AttributeType.MISSION:
                return Mission.class.getClassLoader();

            case AttributeType.SIGNAL:
                return Signal.class.getClassLoader();

            case AttributeType.GUIDED_STATE:
                return GuidedState.class.getClassLoader();

            case AttributeType.TYPE:
                return Type.class.getClassLoader();

            case AttributeType.FOLLOW_STATE:
                return FollowState.class.getClassLoader();

            default:
                return null;
        }
    }

    public void connect(final ConnectionParameter connParams) {
        if (isStarted()) {
            try {
                droneApi.connect(connParams);
                this.connectionParameter = connParams;
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void disconnect() {
        if (isStarted()) {
            try {
                droneApi.disconnect();
                this.connectionParameter = null;
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public boolean isStarted() {
        return droneApi != null && droneApi.asBinder().pingBinder();
    }

    public boolean isConnected() {
        State droneState = getAttribute(AttributeType.STATE);
        return isStarted() && droneState.isConnected();
    }

    public ConnectionParameter getConnectionParameter() {
        return this.connectionParameter;
    }

    private <T extends MissionItem> T buildMissionItem(MissionItem.ComplexItem<T> complexItem) {
        if (isStarted()) {
            try {
                T missionItem = (T) complexItem;
                Bundle payload = missionItem.getType().storeMissionItem(missionItem);
                if (payload == null)
                    return null;

                droneApi.buildComplexMissionItem(payload);
                T updatedItem = MissionItemType.restoreMissionItemFromBundle(payload);
                complexItem.copy(updatedItem);
                return (T) complexItem;
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }

        return null;
    }

    public <T extends MissionItem> void buildMissionItemsAsync(final OnMissionItemsBuiltCallback<T> callback,
                                                               final MissionItem.ComplexItem<T>... missionItems) {
        if (callback == null)
            throw new IllegalArgumentException("Callback must be non-null.");

        if (missionItems == null || missionItems.length == 0)
            return;

        asyncScheduler.execute(new Runnable() {
            @Override
            public void run() {
                for (MissionItem.ComplexItem<T> missionItem : missionItems)
                    buildMissionItem(missionItem);

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onMissionItemsBuilt(missionItems);
                    }
                });
            }
        });
    }

    public void registerDroneListener(DroneListener listener) {
        if (listener == null)
            return;

        droneListeners.add(listener);
    }

    private void addAttributesObserver(IObserver observer) {
        if (isStarted()) {
            try {
                this.droneApi.addAttributesObserver(observer);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void addMavlinkObserver(MavlinkObserver observer) {
        if (isStarted()) {
            try {
                droneApi.addMavlinkObserver(observer);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void removeMavlinkObserver(MavlinkObserver observer) {
        if (isStarted()) {
            try {
                droneApi.removeMavlinkObserver(observer);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void unregisterDroneListener(DroneListener listener) {
        if (listener == null)
            return;

        droneListeners.remove(listener);
    }

    private void removeAttributesObserver(IObserver observer) {
        if (isStarted()) {
            try {
                this.droneApi.removeAttributesObserver(observer);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void changeVehicleMode(VehicleMode newMode) {
        if (isStarted()) {
            try {
                droneApi.changeVehicleMode(newMode);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void refreshParameters() {
        if (isStarted()) {
            try {
                droneApi.refreshParameters();
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void writeParameters(Parameters parameters) {
        if (isStarted()) {
            try {
                droneApi.writeParameters(parameters);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }


    public void setMission(Mission mission, boolean pushToDrone) {
        if (isStarted()) {
            try {
                droneApi.setMission(mission, pushToDrone);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void generateDronie() {
        if (isStarted()) {
            try {
                droneApi.generateDronie();
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void arm(boolean arm) {
        if (isStarted()) {
            try {
                droneApi.arm(arm);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void startMagnetometerCalibration(double[] startPointsX, double[] startPointsY,
                                             double[] startPointsZ) {
        if (isStarted()) {
            try {
                droneApi.startMagnetometerCalibration(startPointsX, startPointsY, startPointsZ);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void stopMagnetometerCalibration() {
        if (isStarted()) {
            try {
                droneApi.stopMagnetometerCalibration();
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void startIMUCalibration() {
        if (isStarted()) {
            try {
                droneApi.startIMUCalibration();
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void sendIMUCalibrationAck(int step) {
        if (isStarted()) {
            try {
                droneApi.sendIMUCalibrationAck(step);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void doGuidedTakeoff(double altitude) {
        if (isStarted()) {
            try {
                droneApi.doGuidedTakeoff(altitude);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void pauseAtCurrentLocation() {
        getAttributeAsync(AttributeType.GPS, new AttributeRetrievedListener<Gps>() {
            @Override
            public void onRetrievalSucceed(Gps gps) {
                sendGuidedPoint(gps.getPosition(), true);
            }
        });
    }

    public void sendGuidedPoint(LatLong point, boolean force) {
        if (isStarted()) {
            try {
                droneApi.sendGuidedPoint(point, force);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void sendMavlinkMessage(MavlinkMessageWrapper messageWrapper) {
        if (messageWrapper != null && isStarted()) {
            try {
                droneApi.sendMavlinkMessage(messageWrapper);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void setGuidedAltitude(double altitude) {
        if (isStarted()) {
            try {
                droneApi.setGuidedAltitude(altitude);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void setGuidedVelocity(double xVel, double yVel, double zVel) {
        if (isStarted()) {
            try {
                droneApi.setGuidedVelocity(xVel, yVel, zVel);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void enableFollowMe(FollowType followType) {
        if (isStarted()) {
            try {
                droneApi.enableFollowMe(followType);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void setFollowMeRadius(double radius) {
        if (isStarted()) {
            try {
                droneApi.setFollowMeRadius(radius);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }


    public void disableFollowMe() {
        if (isStarted()) {
            try {
                droneApi.disableFollowMe();
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }


    public void triggerCamera() {
        if (isStarted()) {
            try {
                droneApi.triggerCamera();
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void epmCommand(boolean release) {
        if (isStarted()) {
            try {
                droneApi.epmCommand(release);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    public void loadWaypoints() {
        if (isStarted()) {
            try {
                droneApi.loadWaypoints();
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    void notifyDroneConnectionFailed(final ConnectionResult result) {
        if (droneListeners.isEmpty())
            return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                for (DroneListener listener : droneListeners)
                    listener.onDroneConnectionFailed(result);
            }
        });
    }

    void notifyAttributeUpdated(final String attributeEvent, final Bundle extras) {
        if (AttributeEvent.STATE_UPDATED.equals(attributeEvent)) {
            getAttributeAsync(AttributeType.STATE, new OnAttributeRetrievedCallback<State>() {
                @Override
                public void onRetrievalSucceed(State state) {
                    if (state.isFlying())
                        resetFlightTimer();
                    else
                        stopTimer();
                }

                @Override
                public void onRetrievalFailed() {
                    stopTimer();
                }
            });
        } else if (AttributeEvent.SPEED_UPDATED.equals(attributeEvent)) {
            checkForGroundCollision();
        }

        if (droneListeners.isEmpty())
            return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                for (DroneListener listener : droneListeners)
                    listener.onDroneEvent(attributeEvent, extras);
            }
        });
    }

    void notifyDroneServiceInterrupted(final String errorMsg) {
        if (droneListeners.isEmpty())
            return;

        handler.post(new Runnable() {
            @Override
            public void run() {
                for (DroneListener listener : droneListeners)
                    listener.onDroneServiceInterrupted(errorMsg);
            }
        });
    }
}