package com.sos.jitl.dailyplan.db;

import com.sos.hibernate.classes.SOSHibernateSession;
import com.sos.hibernate.classes.UtcTimeHelper;
import com.sos.hibernate.exceptions.SOSHibernateException;
import com.sos.jitl.dailyplan.job.CheckDailyPlanOptions;
import com.sos.jitl.dailyplan.job.CreateDailyPlanOptions;
import com.sos.jitl.inventory.db.DBLayerInventory;
import com.sos.jitl.reporting.db.DBItemInventoryInstance;
import com.sos.jitl.reporting.db.DBItemInventoryJob;
import com.sos.jitl.reporting.db.DBItemInventoryOrder;
import com.sos.jitl.reporting.db.DBItemInventorySchedule;
import com.sos.jitl.reporting.db.DBLayerReporting;
import com.sos.scheduler.model.SchedulerObjectFactory;
import com.sos.scheduler.model.SchedulerObjectFactory.enu4What;
import com.sos.scheduler.model.answers.*;
import com.sos.scheduler.model.commands.JSCmdShowCalendar;
import com.sos.scheduler.model.commands.JSCmdShowOrder;
import com.sos.scheduler.model.commands.JSCmdShowState;
import com.sos.scheduler.model.objects.Spooler;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Calendar2DB {

    private static final int DEFAULT_DAYS_OFFSET = 365;
    private static final int AVERAGE_DURATION_ONE_ITEM = 5;
    private static final int LIMIT_CALENDAR_CALL = 19999;
    private static final int DAYLOOP = 3;
    private static final int DEFAULT_LIMIT = 30;
    private static final Logger LOGGER = Logger.getLogger(Calendar2DB.class);
    private static SchedulerObjectFactory schedulerObjectFactory = null;
    private Date from;
    private Date to;
    private int dayOffset;
    private String schedulerId = "";

    private String dateFormat = "yyyy-MM-dd'T'HH:mm:ss";
    private DailyPlanDBLayer dailyPlanDBLayer;
    private CreateDailyPlanOptions options = null;
    private CheckDailyPlanOptions checkDailyPlanOptions;
    private sos.spooler.Spooler spooler;
    List<DailyPlanDBItem> dailyPlanList;
    Map<String, DailyPlanCalender2DBFilter> listOfDailyPlanCalender2DBFilter;
    List<DailyPlanCalendarItem> listOfCalendars;
    private DailyPlanInterval dailyPlanInterval;
    private DBLayerInventory dbLayerInventory;
    private Long instanceId;
    private HashMap<String, Order> listOfOrders;

    public Calendar2DB(SOSHibernateSession session) throws Exception {
        dailyPlanDBLayer = new DailyPlanDBLayer(session);
        listOfOrders = new HashMap<String, Order>();
    }

    public void beginTransaction() throws Exception {
        dailyPlanDBLayer.getSession().beginTransaction();
    }

    public void commit() throws Exception {
        dailyPlanDBLayer.getSession().commit();
    }

    public void rollback() throws Exception {
        dailyPlanDBLayer.getSession().rollback();
    }

    public void store() throws Exception {
        final long timeStartAll = System.currentTimeMillis();
        initSchedulerConnection();
        fillListOfCalendars();
        final long timeStart = System.currentTimeMillis();
        store(null);
        final long timeEnd = System.currentTimeMillis();
        LOGGER.debug("Duration store: " + (timeEnd - timeStart) + " ms");
        checkDaysSchedule();
        final long timeEndAll = System.currentTimeMillis();
        LOGGER.debug("Duration total store " + (timeEndAll - timeStartAll) + " ms");

    }

    public void addDailyplan2DBFilter(DailyPlanCalender2DBFilter dailyPlanCalender2DBFilter) throws SOSHibernateException, ParseException {
        if (listOfDailyPlanCalender2DBFilter == null) {
            initSchedulerConnection();
            listOfDailyPlanCalender2DBFilter = new HashMap<String, DailyPlanCalender2DBFilter>();
        }
        if ((!"".equals(dailyPlanCalender2DBFilter.getForSchedule()) && (dailyPlanCalender2DBFilter.getForSchedule() != null))) {
            if (dbLayerInventory == null) {
                dbLayerInventory = new DBLayerInventory(dailyPlanDBLayer.getSession());
                DBItemInventoryInstance dbItemInventoryInstance;
                dbItemInventoryInstance = dbLayerInventory.getInventoryInstance(options.commandUrl.getValue());
                if (dbItemInventoryInstance != null) {
                    instanceId = dbItemInventoryInstance.getId();
                }
            }

            if (instanceId != null) {
                String schedule;
                DBItemInventorySchedule dbItemInventorySchedule = dbLayerInventory.getSubstituteIfExists(dailyPlanCalender2DBFilter.getForSchedule(),
                        instanceId);
                if (dbItemInventorySchedule != null && !".".equals(dbItemInventorySchedule.getSubstituteName())) {
                    schedule = dbItemInventorySchedule.getSubstituteName();
                } else {
                    schedule = dailyPlanCalender2DBFilter.getForSchedule();
                }
                List<DBItemInventoryJob> listOfUsedJobs = dbLayerInventory.getJobsReferencingSchedule(instanceId, schedule);
                for (DBItemInventoryJob dbItemInventoryJob : listOfUsedJobs) {
                    dailyPlanCalender2DBFilter = new DailyPlanCalender2DBFilter();
                    dailyPlanCalender2DBFilter.setForJob(dbItemInventoryJob.getName());
                    listOfDailyPlanCalender2DBFilter.put(dailyPlanCalender2DBFilter.getKey(), dailyPlanCalender2DBFilter);

                }
                List<DBItemInventoryOrder> listOfUsedOrders = dbLayerInventory.getOrdersReferencingSchedule(instanceId, schedule);
                for (DBItemInventoryOrder dbItemInventoryOrder : listOfUsedOrders) {
                    dailyPlanCalender2DBFilter = new DailyPlanCalender2DBFilter();
                    dailyPlanCalender2DBFilter.setForJobChain(dbItemInventoryOrder.getJobChainName());
                    dailyPlanCalender2DBFilter.setForOrderId(dbItemInventoryOrder.getOrderId());
                    listOfDailyPlanCalender2DBFilter.put(dailyPlanCalender2DBFilter.getKey(), dailyPlanCalender2DBFilter);

                }
            }
        } else {
            listOfDailyPlanCalender2DBFilter.put(dailyPlanCalender2DBFilter.getKey(), dailyPlanCalender2DBFilter);
        }
    }

    public void processDailyplan2DBFilter() throws Exception {
        long timeStartAll = System.currentTimeMillis();
        long timeStoreSum = 0L;
        if (listOfDailyPlanCalender2DBFilter == null) {
            listOfDailyPlanCalender2DBFilter = new HashMap<String, DailyPlanCalender2DBFilter>();
        }
        initSchedulerConnection();
        fillListOfCalendars();

        DailyPlanCalendarItem dailyPlanCalendarItem = listOfCalendars.get(0);
        long timeGetStart = System.currentTimeMillis();
        HashMap<String, Period> calendarEntries = new HashMap<String, Period>();
        for (Object calendarObject : dailyPlanCalendarItem.getCalendar().getAtOrPeriod()) {
            if (calendarObject instanceof Period) {
                Period period = (Period) calendarObject;
                String orderId = period.getOrder();
                String jobChain = period.getJobChain();
                String job = period.getJob();
                calendarEntries.put(job + ":" + jobChain + ":" + orderId, period);
            }
        }
        long numberOfDailyPlanItems = calendarEntries.size();
        long numberOfFilters = listOfDailyPlanCalender2DBFilter.size();
        long estimatedDurationAll = AVERAGE_DURATION_ONE_ITEM * numberOfDailyPlanItems * dayOffset;
        long estimatatedDurationSelect = AVERAGE_DURATION_ONE_ITEM * numberOfFilters * dayOffset;

        long percentage = 0;
        if (estimatedDurationAll > 0) {
            percentage = 100 * estimatatedDurationSelect / estimatedDurationAll;
        }

        LOGGER.debug("-> estimated all: " + estimatedDurationAll);
        LOGGER.debug("-> estimated selected: " + estimatatedDurationSelect);
        LOGGER.debug("-> duration percentage selected: " + percentage);

        if (percentage < 90) {

            for (Map.Entry<String, DailyPlanCalender2DBFilter> entry : listOfDailyPlanCalender2DBFilter.entrySet()) {
                DailyPlanCalender2DBFilter dailyPlanCalender2DBFilter = entry.getValue();
                final long timeStart = System.currentTimeMillis();
                store(dailyPlanCalender2DBFilter);
                final long timeEnd = System.currentTimeMillis();
                LOGGER.debug("Duration: " + dailyPlanCalender2DBFilter.getName() + ":" + (timeEnd - timeStart) + " ms");
                timeStoreSum = timeStoreSum + (timeEnd - timeStart);
            }
        } else {
            long timeStoreStart = System.currentTimeMillis();
            store(null);
            final long timeStoreEnd = System.currentTimeMillis();
            timeStoreSum = timeStoreSum + (timeStoreEnd - timeStoreStart);
        }
        LOGGER.debug("Duration total: " + timeStoreSum + " ms");
        checkDaysSchedule();
        long timeEndAll = System.currentTimeMillis();
        LOGGER.debug("Duration process all: " + (timeEndAll - timeStartAll) + " ms");
    }

    private void fillListOfCalendars() {
        if (listOfCalendars == null) {
            listOfCalendars = new ArrayList<DailyPlanCalendarItem>();
        }

        dailyPlanInterval = new DailyPlanInterval(from, to);

        while (from.before(to)) {
            Date before = addCalendar(from, DAYLOOP, java.util.Calendar.DAY_OF_MONTH);
            if (to.before(before)) {
                before = to;
            }
            Date xFrom = from;
            Calendar calendar = getCalendar(from, before);
            DailyPlanCalendarItem dailyPlanCalendarItem = new DailyPlanCalendarItem(xFrom, before, calendar);
            listOfCalendars.add(dailyPlanCalendarItem);
        }
    }

    private void initSchedulerConnection() throws ParseException {
        if ("".equals(schedulerId)) {
            LOGGER.debug("Calender2DB");
            if (spooler == null) {
                schedulerObjectFactory = new SchedulerObjectFactory(options.getCommandUrl().getValue());
            } else {
                schedulerObjectFactory = new SchedulerObjectFactory(spooler);
            }
            schedulerObjectFactory.initMarshaller(Spooler.class);

            schedulerId = this.getSchedulerId();
            if (options.dayOffset.isDirty()) {
                dayOffset = options.getdayOffset().value();
            } else {
                dayOffset = getDayOffsetFromPlan();
            }

            setFrom();
            setTo();

        }
    }

    private int getDayOffsetFromPlan() {
        Date maxPlannedTime = dailyPlanDBLayer.getMaxPlannedStart(schedulerId);
        Date today = new Date();
        int days = (int) ((maxPlannedTime.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
        if (days == 0) {
            days = DEFAULT_DAYS_OFFSET;
        }
        return days;
    }

    private Calendar getCalendar(Date start, Date before) {
        JSCmdShowCalendar jsCmdShowCalendar = schedulerObjectFactory.createShowCalendar();
        jsCmdShowCalendar.setWhat("orders");
        jsCmdShowCalendar.setLimit(LIMIT_CALENDAR_CALL);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00");
        jsCmdShowCalendar.setFrom(sdf.format(start));
        String s = sdf.format(start);
        sdf = new SimpleDateFormat("yyyy-MM-dd'T'23:59:59");
        jsCmdShowCalendar.setBefore(sdf.format(before));
        from = addCalendar(before, 1, java.util.Calendar.DAY_OF_MONTH);

        String b = sdf.format(before);
        jsCmdShowCalendar.run();
        return jsCmdShowCalendar.getCalendar();
    }

    private void getCurrentDailyPlan(DailyPlanCalender2DBFilter dailyPlanCalender2DBFilter) throws Exception {
        String toTimeZoneString = "UTC";
        String fromTimeZoneString = DateTimeZone.getDefault().getID();
        LOGGER.debug("fromTimeZone:" + fromTimeZoneString);
        LOGGER.debug("toTimeZone:" + toTimeZoneString);
        LOGGER.debug("intervall from:" + dailyPlanInterval.getFrom());
        LOGGER.debug("intervall to:" + dailyPlanInterval.getTo());
        DateTime utcFrom = new DateTime(dailyPlanInterval.getFrom()).withZone(DateTimeZone.UTC);
        utcFrom = utcFrom.withZone(DateTimeZone.UTC);
        DateTime utcTo = new DateTime(dailyPlanInterval.getTo()).withZone(DateTimeZone.UTC);
        utcTo = utcTo.withZone(DateTimeZone.UTC);

        Date convertedFrom = UtcTimeHelper.convertTimeZonesToDate(fromTimeZoneString, toTimeZoneString, utcFrom);
        Date convertedTo = UtcTimeHelper.convertTimeZonesToDate(fromTimeZoneString, toTimeZoneString, new DateTime(utcTo));
        LOGGER.debug("converted intervall from:" + convertedFrom);
        LOGGER.debug("convertet intervall to:" + convertedTo);

        dailyPlanDBLayer.setWhereFrom(convertedFrom);
        dailyPlanDBLayer.setWhereTo(convertedTo);
        dailyPlanDBLayer.setWhereSchedulerId(schedulerId);
        if (dailyPlanCalender2DBFilter != null) {
            dailyPlanDBLayer.getFilter().setCalender2DBFilter(dailyPlanCalender2DBFilter);
        }

        dailyPlanList = dailyPlanDBLayer.getDailyPlanList(0);
    }

    private String getSchedulerId() {
        JSCmdShowState jsCmdShowState = schedulerObjectFactory.createShowState(new enu4What[] { enu4What.folders, enu4What.no_subfolders });
        jsCmdShowState.setPath("notexist_sos");
        jsCmdShowState.setSubsystems("folder");
        jsCmdShowState.setMaxTaskHistory(BigInteger.valueOf(1));

        jsCmdShowState.run();

        State objState = jsCmdShowState.getState();
        return objState.getSpoolerId();
    }

    private boolean isSetback(Order order) {
        return order.getSetback() != null;
    }

    private Order getOrder(String jobChain, String orderId) {
        if (orderId == null) {
            return null;
        } else {
            String orderKey = jobChain + "(" + orderId + ")";
            Order order = listOfOrders.get(orderKey);

            if (order == null) {
                JSCmdShowOrder jsCmdShowOrder = schedulerObjectFactory.createShowOrder();
                jsCmdShowOrder.setJobChain(jobChain);
                jsCmdShowOrder.setOrder(orderId);
                jsCmdShowOrder.run();

                order = jsCmdShowOrder.getAnswer().getOrder();
                listOfOrders.put(orderKey, order);
            }
            return order;
        }
    }

    private Date addCalendar(Date date, Integer add, Integer c) {
        java.util.Calendar calendar = new GregorianCalendar();
        calendar.setTime(date);
        calendar.add(c, add);
        return calendar.getTime();
    }

    private void store(DailyPlanCalender2DBFilter dailyPlanCalender2DBFilter) throws Exception {
        DBLayerReporting dbLayerReporting = new DBLayerReporting(dailyPlanDBLayer.getSession());

        int i = 0;
        boolean isNew = false;

        this.getCurrentDailyPlan(dailyPlanCalender2DBFilter);

        for (DailyPlanCalendarItem dailyPlanCalendarItem : listOfCalendars) {

            from = dailyPlanCalendarItem.getFrom();
            to = dailyPlanCalendarItem.getTo();

            for (Object calendarObject : dailyPlanCalendarItem.getCalendar().getAtOrPeriod()) {
                Order order = null;
                String job = null;
                String jobChain = null;
                DailyPlanDBItem dailyPlanDBItem;

                if (i < dailyPlanList.size()) {
                    isNew = false;
                    dailyPlanDBItem = dailyPlanList.get(i);
                    dailyPlanDBItem.setIsAssigned(false);
                    dailyPlanDBItem.setIsLate(false);
                    dailyPlanDBItem.setJob(null);
                    dailyPlanDBItem.setJobChain(null);
                    dailyPlanDBItem.setOrderId(null);
                    dailyPlanDBItem.nullPlannedStart();
                    dailyPlanDBItem.setExpectedEnd(null);
                    dailyPlanDBItem.nullPeriodBegin();
                    dailyPlanDBItem.nullPeriodEnd();
                    dailyPlanDBItem.setRepeatInterval(null);
                    dailyPlanDBItem.setStartStart(false);
                    dailyPlanDBItem.setState("");
                    dailyPlanDBItem.setReportTriggerId(null);
                    dailyPlanDBItem.setReportExecutionId(null);
                    dailyPlanDBItem.setDateFormat(this.dateFormat);
                } else {
                    isNew = true;
                    dailyPlanDBItem = new DailyPlanDBItem(this.dateFormat);
                    dailyPlanDBItem.setCreated(new Date());
                }

                dailyPlanDBItem.setSchedulerId(String.valueOf(i));
                dailyPlanDBItem.setState("PLANNEDFORUPDATE");

                if (calendarObject instanceof Period) {

                    Period period = (Period) calendarObject;
                    String orderId = period.getOrder();
                    String singleStart = period.getSingleStart();

                    jobChain = period.getJobChain();
                    job = period.getJob();

                    boolean handleEntry = (dailyPlanCalender2DBFilter == null || dailyPlanCalender2DBFilter.handleEntry(job, jobChain, orderId));

                    if (handleEntry) {

                        i = i + 1;

                        order = getOrder(jobChain, orderId);

                        if (singleStart != null) {
                            if (orderId == null || !isSetback(order)) {
                                dailyPlanDBItem.setPlannedStart(singleStart);
                                LOGGER.debug("Start at :" + singleStart);
                                LOGGER.debug("Job Name :" + job);
                                LOGGER.debug("Job-Chain Name :" + jobChain);
                                LOGGER.debug("Order Name :" + orderId);
                            } else {
                                LOGGER.debug("Job-Chain Name :" + jobChain + "/" + orderId + " ignored because order is in setback state");
                            }
                        } else {
                            dailyPlanDBItem.setPeriodBegin(period.getBegin());
                            dailyPlanDBItem.setPeriodEnd(period.getEnd());
                            dailyPlanDBItem.setRepeatInterval(period.getAbsoluteRepeat(), period.getRepeat());
                            LOGGER.debug("Absolute Repeat Interval :" + period.getAbsoluteRepeat());
                            LOGGER.debug("Timerange start :" + period.getBegin());
                            LOGGER.debug("Timerange end :" + period.getEnd());
                            LOGGER.debug("Job-Name :" + period.getJob());
                        }
                        dailyPlanDBItem.setJob(job);
                        dailyPlanDBItem.setJobChain(jobChain);
                        dailyPlanDBItem.setOrderId(orderId);

                        Long duration = 0L;
                        if (order == null) {
                            duration = dbLayerReporting.getTaskEstimatedDuration(job, DEFAULT_LIMIT);
                        } else {
                            duration = dbLayerReporting.getOrderEstimatedDuration(order, DEFAULT_LIMIT);
                        }
                        dailyPlanDBItem.setExpectedEnd(new Date(dailyPlanDBItem.getPlannedStart().getTime() + duration));
                        dailyPlanDBItem.setIsAssigned(false);
                        dailyPlanDBItem.setModified(new Date());
                        if (dailyPlanDBItem.getPlannedStart() != null && (dailyPlanDBItem.getJob() == null || !"(Spooler)".equals(dailyPlanDBItem
                                .getJob()))) {
                            if (isNew) {
                                dailyPlanDBLayer.getSession().save(dailyPlanDBItem);
                            } else {
                                dailyPlanDBLayer.getSession().update(dailyPlanDBItem);
                            }
                        }
                    }
                }
            }
        }
        for (int ii = i; ii < dailyPlanList.size(); ii++) {
            DailyPlanDBItem dailyPlanDBItem = dailyPlanList.get(ii);
            dailyPlanDBLayer.getSession().delete(dailyPlanDBItem);
        }
        dailyPlanDBLayer.updateDailyPlanList(schedulerId);
    }

    private void checkDaysSchedule() throws Exception {
        DailyPlanAdjustment dailyPlanAdjustment = new DailyPlanAdjustment(dailyPlanDBLayer.getSession());
        checkDailyPlanOptions = new CheckDailyPlanOptions();
        try {
            checkDailyPlanOptions.dayOffset.value(options.dayOffset.value());
            if (schedulerId != null) {
                checkDailyPlanOptions.scheduler_id.setValue(schedulerId);
            }
            dailyPlanAdjustment.setOptions(checkDailyPlanOptions);
            dailyPlanAdjustment.setTo(new Date());
            dailyPlanAdjustment.adjustWithHistory();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            dailyPlanAdjustment.rollback();
            throw new Exception(e);
        }
    }

    private void setFrom() throws ParseException {
        Date now = new Date();
        if (dayOffset < 0) {
            now = addCalendar(now, dayOffset, java.util.Calendar.DAY_OF_MONTH);
        }
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        String froms = formatter.format(now);
        froms = froms + "T00:00:00";
        formatter = new SimpleDateFormat(dateFormat);
        this.from = formatter.parse(froms);
    }

    private void setTo() throws ParseException {
        Date now = new Date();
        if (dayOffset > 0) {
            now = addCalendar(now, dayOffset, java.util.Calendar.DAY_OF_MONTH);
        }
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        String tos = formatter.format(now);
        tos = tos + "T23:59:59";
        formatter = new SimpleDateFormat(dateFormat);
        this.to = formatter.parse(tos);
    }

    public Date getFrom() {
        return from;
    }

    public Date getTo() {
        return to;
    }

    public void setOptions(CreateDailyPlanOptions options) throws ParseException {
        this.options = options;
    }

    public void setSpooler(sos.spooler.Spooler spooler) {
        this.spooler = spooler;
    }

}