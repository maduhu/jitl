package com.sos.jitl.notification.model;

import java.io.File;
import java.util.Locale;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Period;
import org.joda.time.PeriodType;
import org.joda.time.format.PeriodFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sos.hibernate.classes.SOSHibernateSession;
import com.sos.jitl.notification.db.DBLayerSchedulerMon;
import com.sos.jitl.notification.helper.RegExFilenameFilter;

import sos.util.SOSString;

public class NotificationModel {

    final Logger logger = LoggerFactory.getLogger(NotificationModel.class);
    DBLayerSchedulerMon dbLayer = null;

    public static final String OPERATION_ACKNOWLEDGE = "acknowledge";
    public static final String OPERATION_RESET_SERVICES = "reset_services";
   
    public enum NotificationType {
        ERROR, SUCCESS, RECOVERY, CHECK
    }

    public NotificationModel(){}
    
    public NotificationModel(SOSHibernateSession sess) throws Exception {
        if (sess == null) {
            throw new Exception("connection is NULL");
        }
        dbLayer = new DBLayerSchedulerMon(sess);
    }

    public void setConnection(SOSHibernateSession conn) throws Exception {
        if (conn == null) {
            throw new Exception("connection is NULL");
        }
        dbLayer = new DBLayerSchedulerMon(conn);
    }

    public DBLayerSchedulerMon getDbLayer() {
        return dbLayer;
    }

    public static File[] getFiles(File dir, String regex) {

        return dir.listFiles(new RegExFilenameFilter(regex));
    }

    public static File[] getAllConfigurationFiles(File dir) {
        String regex = "^SystemMonitorNotificationTimers\\.xml$|(^SystemMonitorNotification_){1}(.)*\\.xml$";

        return getFiles(dir, regex);
    }

    public static File[] getConfigurationFiles(File dir) {
        String regex = "(^SystemMonitorNotification_){1}(.)*\\.xml$";

        return getFiles(dir, regex);
    }

    public static File getTimerConfigurationFileX(File dir) {
        File f = new File(dir, "SystemMonitorNotificationTimers.xml");
        return f.exists() ? f : null;
    }

    public static File getConfigurationSchemaFile(File dir) {
        String regex = "(^SystemMonitorNotification_){1}(.)*\\.xsd$";

        File[] result = getFiles(dir, regex);
        if (result.length > 0) {
            return result[0];
        }
        return null;
    }

    public static String getDuration(DateTime startTime, DateTime endTime) {
        Duration duration = new Duration(startTime, endTime);
        Period period = duration.toPeriod().normalizedStandard(PeriodType.time());
        return PeriodFormat.wordBased(Locale.ENGLISH).print(period);
    }

    public static int resolveAge2Minutes(String age) throws Exception {
        if (SOSString.isEmpty(age)) {
            throw new Exception("age is empty");
        }

        int minutes = 0;
        String[] arr = age.trim().split(" ");
        for (String s : arr) {
            s = s.trim().toLowerCase();
            if (!SOSString.isEmpty(s)) {
                String sub = s;
                try {
                    if (s.endsWith("w")) {
                        sub = s.substring(0, s.length() - 1);
                        minutes += 60 * 24 * 7 * Integer.parseInt(sub);
                    } else if (s.endsWith("d")) {
                        sub = s.substring(0, s.length() - 1);
                        minutes += 60 * 24 * Integer.parseInt(sub);
                    } else if (s.endsWith("h")) {
                        sub = s.substring(0, s.length() - 1);
                        minutes += 60 * Integer.parseInt(sub);
                    } else if (s.endsWith("m")) {
                        sub = s.substring(0, s.length() - 1);
                        minutes += Integer.parseInt(sub);
                    } else {
                        minutes += Integer.parseInt(sub);
                    }
                } catch (Exception ex) {
                    throw new Exception(String.format("invalid integer value = %s (%s) : %s", sub, s, ex.toString()));
                }
            }
        }
        return minutes;
    }

}
