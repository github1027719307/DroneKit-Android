package com.o3dr.services.android.lib.drone.attribute;

/**
 * Holds handles used to retrieve additional information broadcast along a drone event.
 */
public class AttributeEventExtra {

    private static final String PACKAGE_NAME = "com.o3dr.services.android.lib.attribute.event.extra";

    /**
     * Used to access autopilot error type.
     * @see {@link com.o3dr.services.android.lib.drone.attribute.error.ErrorType}
     * @see {@link com.o3dr.services.android.lib.drone.attribute.AttributeEvent#AUTOPILOT_ERROR}
     */
    public static final String EXTRA_AUTOPILOT_ERROR_ID = PACKAGE_NAME + ".AUTOPILOT_ERROR_ID";

    /**
     * Used to access autopilot messages.
     * @see {@link com.o3dr.services.android.lib.drone.attribute.AttributeEvent#AUTOPILOT_MESSAGE}
     */
    public static final String EXTRA_AUTOPILOT_MESSAGE = PACKAGE_NAME + ".AUTOPILOT_MESSAGE";

    public static final String EXTRA_AUTOPILOT_MESSAGE_LEVEL = PACKAGE_NAME + ".AUTOPILOT_MESSAGE_LEVEL";

    /**
     * Used to access messages origination from the imu calibration process.
     */
    public static final String EXTRA_CALIBRATION_IMU_MESSAGE = PACKAGE_NAME + ".CALIBRATION_IMU_MESSAGE";

    /**
     * Used to access the mavlink version when the heartbeat is received for the first time or
     * restored.
     */
    public static final String EXTRA_MAVLINK_VERSION = PACKAGE_NAME + ".MAVLINK_VERSION";

    public static final String EXTRA_MISSION_CURRENT_WAYPOINT = PACKAGE_NAME + "" +
            ".MISSION_CURRENT_WAYPOINT";
    public static final String EXTRA_MISSION_DRONIE_BEARING = PACKAGE_NAME + "" +
            ".MISSION_DRONIE_BEARING";

    /**
     * Used to retrieve the count of the set of parameters being refreshed.
     * @see {@link AttributeEvent#PARAMETER_RECEIVED}
     */
    public static final String EXTRA_PARAMETERS_COUNT = PACKAGE_NAME + ".PARAMETERS_COUNT";

    /**
     * Used to retrieve the index of the received parameter.
     * @see {@link AttributeEvent#PARAMETER_RECEIVED}
     */
    public static final String EXTRA_PARAMETER_INDEX = PACKAGE_NAME + ".PARAMETER_INDEX";

    /**
     * Used to retrieve the name of the received parameter.
     * @see {@link AttributeEvent#PARAMETER_RECEIVED}
     */
    public static final String EXTRA_PARAMETER_NAME = PACKAGE_NAME + ".PARAMETER_NAME";

    /**
     * Used to retrieve the value of the received parameter.
     * @see {@link AttributeEvent#PARAMETER_RECEIVED}
     */
    public static final String EXTRA_PARAMETER_VALUE = PACKAGE_NAME + ".PARAMETER_VALUE";
}