package com.sos.jitl.reporting.model.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.hibernate.Criteria;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sos.hibernate.classes.SOSHibernateSession;
import com.sos.jitl.classes.plugin.PluginMailer;
import com.sos.jitl.notification.helper.NotificationReportExecution;
import com.sos.jitl.reporting.db.DBItemReportExecution;
import com.sos.jitl.reporting.db.DBItemReportInventoryInfo;
import com.sos.jitl.reporting.db.DBItemReportTrigger;
import com.sos.jitl.reporting.db.DBItemReportVariable;
import com.sos.jitl.reporting.db.DBItemSchedulerHistory;
import com.sos.jitl.reporting.db.DBItemSchedulerHistoryOrderStepReporting;
import com.sos.jitl.reporting.db.DBItemSchedulerOrderStepHistory;
import com.sos.jitl.reporting.helper.CounterRemove;
import com.sos.jitl.reporting.helper.CounterSynchronize;
import com.sos.jitl.reporting.helper.EStartCauses;
import com.sos.jitl.reporting.helper.ReportUtil;
import com.sos.jitl.reporting.helper.TriggerResult;
import com.sos.jitl.reporting.job.report.FactJobOptions;
import com.sos.jitl.reporting.model.IReportingModel;
import com.sos.jitl.reporting.model.ReportingModel;
import com.sos.jitl.reporting.plugin.FactNotificationPlugin;

import sos.util.SOSString;

public class FactModel extends ReportingModel implements IReportingModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(FactModel.class);
    private static final String TABLE_REPORTING_VARIABLES_VARIABLE_PREFIX = "reporting_";
    private FactJobOptions options;
    private SOSHibernateSession schedulerSession;
    private CounterRemove counterOrderRemoved;
    private CounterRemove counterStandaloneRemoved;
    private CounterRemove counterOrderUncompletedRemoved;
    private CounterRemove counterStandaloneUncompletedRemoved;
    private CounterSynchronize counterOrderSyncUncompleted;
    private CounterSynchronize counterOrderSync;
    private CounterSynchronize counterStandaloneSyncUncompleted;
    private CounterSynchronize counterStandaloneSync;
    private boolean isChanged = false;
    private boolean isOrderChanged = false;
    private boolean isStandaloneChanged = false;
    private boolean isOrderChangedOnStandalone = false;
    private int maxHistoryAge;
    private int maxUncompletedAge;
    private Long maxHistoryTasks;
    private Optional<Integer> largeResultFetchSizeReporting = Optional.empty();
    private Optional<Integer> largeResultFetchSizeScheduler = Optional.empty();
    private ArrayList<Long> synchronizedOrderTaskIds;
    private FactNotificationPlugin notificationPlugin;

    public FactModel(SOSHibernateSession reportingSess, SOSHibernateSession schedulerSess, FactJobOptions opt) throws Exception {
        setReportingSession(reportingSess);
        schedulerSession = schedulerSess;
        options = opt;

        largeResultFetchSizeReporting = getFetchSize(options.large_result_fetch_size.value());
        largeResultFetchSizeScheduler = getFetchSize(options.large_result_fetch_size_scheduler.value());
        maxHistoryAge = ReportUtil.resolveAge2Minutes(options.max_history_age.getValue());
        maxHistoryTasks = new Long(options.max_history_tasks.value());
        maxUncompletedAge = ReportUtil.resolveAge2Minutes(options.max_uncompleted_age.getValue());
        registerPlugin();
    }

    public void init(PluginMailer mailer, Path configDirectory) {
        pluginOnInit(mailer, configDirectory);
    }

    public void exit() {
        pluginOnExit();
    }

    @Override
    public void process() throws Exception {
        String method = "process";
        Date dateFrom = null;
        Date dateTo = ReportUtil.getCurrentDateTime();
        Long dateToAsMinutes = dateTo.getTime() / 1000 / 60;
        DateTime start = new DateTime();
        try {
            LOGGER.debug(String.format("%s: execute_notification_plugin = %s", method, options.execute_notification_plugin.value()));
            initCounters();

            DBItemReportVariable reportingVariable = initSynchronizing();
            dateFrom = getDateFrom(reportingVariable, dateTo);

            removeOrder(options.current_scheduler_id.getValue(), dateFrom, dateTo);
            synchronizeOrderUncompleted(options.current_scheduler_id.getValue(), dateToAsMinutes);
            synchronizeOrder(options.current_scheduler_id.getValue(), dateFrom, dateTo, dateToAsMinutes);

            removeStandalone(options.current_scheduler_id.getValue(), dateFrom, dateTo);
            synchronizeStandaloneUncompleted(options.current_scheduler_id.getValue(), dateToAsMinutes);
            synchronizeStandalone(options.current_scheduler_id.getValue(), dateFrom, dateTo, dateToAsMinutes, synchronizedOrderTaskIds);

            finishSynchronizing(reportingVariable, dateTo);
            setChangedSummary();
            logSummary(dateFrom, dateTo, start);
        } catch (Exception ex) {
            throw new Exception(String.format("%s: %s", method, ex.toString()), ex);
        }
    }

    private void removeOrder(String schedulerId, Date dateFrom, Date dateTo) throws Exception {
        counterOrderRemoved = getDbLayer().removeOrder(schedulerId, dateFrom, dateTo);
    }

    private void removeStandalone(String schedulerId, Date dateFrom, Date dateTo) throws Exception {
        counterStandaloneRemoved = getDbLayer().removeStandalone(schedulerId, dateFrom, dateTo);
    }

    private void removeOrderUncompleted(String schedulerId, List<Long> ids) throws Exception {
        counterOrderUncompletedRemoved = getDbLayer().removeOrderUncompleted(schedulerId, ids);
    }

    private void removeStandaloneUncompleted(String schedulerId, List<Long> ids) throws Exception {
        counterStandaloneUncompletedRemoved = getDbLayer().removeStandaloneUncompleted(schedulerId, ids);
    }

    private void finishSynchronizing(DBItemReportVariable reportingVariable, Date dateTo) throws Exception {
        String method = "finishSynchronizing";
        try {
            LOGGER.debug(String.format("%s: dateTo = %s", method, ReportUtil.getDateAsString(dateTo)));

            synchronizedOrderTaskIds = null;

            getDbLayer().getSession().beginTransaction();
            reportingVariable.setNumericValue(new Long(maxHistoryAge));
            reportingVariable.setTextValue(ReportUtil.getDateAsString(dateTo));
            getDbLayer().updateReportVariable(reportingVariable);
            getDbLayer().getSession().commit();
        } catch (Exception e) {
            try {
                getDbLayer().getSession().rollback();
            } catch (Exception ex) {
                LOGGER.warn(String.format("%s: %s", method, ex.toString()), ex);
            }
            throw new Exception(String.format("%s: %s", method, e.toString()), e);
        }
    }

    private DBItemReportVariable initSynchronizing() throws Exception {
        String method = "initSynchronizing";
        DBItemReportVariable variable = null;
        try {
            String name = getSchedulerVariableName();
            LOGGER.debug(String.format("%s, name=%s", method, name));

            synchronizedOrderTaskIds = new ArrayList<Long>();
            getDbLayer().getSession().beginTransaction();
            variable = getDbLayer().getReportVariabe(name);
            if (variable == null) {
                variable = getDbLayer().createReportVariable(name, new Long(maxHistoryAge), null);
            }
            getDbLayer().getSession().commit();
        } catch (Exception e) {
            try {
                getDbLayer().getSession().rollback();
            } catch (Exception ex) {
                LOGGER.warn(String.format("%s: %s", method, ex.toString()), ex);
            }
            throw new Exception(String.format("%s: %s", method, e.toString()), e);
        }
        return variable;
    }

    private String getSchedulerVariableName() {
        String name = String.format("%s%s", TABLE_REPORTING_VARIABLES_VARIABLE_PREFIX, options.current_scheduler_id.getValue());
        if (name.length() > 255) {
            name = name.substring(0, 255);
        }
        return name.toLowerCase();
    }

    private void synchronizeOrderUncompleted(String schedulerId, Long dateToAsMinutes) throws Exception {
        String method = "synchronizeOrderUncompleted";
        LOGGER.debug(String.format("%s", method));
        if (schedulerId != null && !schedulerId.isEmpty()) {
            ArrayList<Long> triggerIds = new ArrayList<Long>();
            ArrayList<Long> orderHistoryIds = new ArrayList<Long>();
            ScrollableResults sr = null;
            try {
                getDbLayer().getSession().beginTransaction();
                Criteria cr = getDbLayer().getOrderSyncUncomplitedIds(largeResultFetchSizeReporting, schedulerId);
                sr = cr.scroll(ScrollMode.FORWARD_ONLY);
                int i = 0;
                while (sr.next()) {
                    i++;
                    if (i <= SOSHibernateSession.LIMIT_IN_CLAUSE) {
                        triggerIds.add((Long) sr.get(0));
                    } else {
                        triggerIds = null;
                    }
                    orderHistoryIds.add((Long) sr.get(1));
                }
                sr.close();
                sr = null;
                getDbLayer().getSession().commit();
            } catch (Exception e) {
                try {
                    getDbLayer().getSession().rollback();
                } catch (Exception ex) {
                    LOGGER.warn(String.format("%s: %s", method, ex.toString()), ex);
                }
                throw new Exception(String.format("%s: error on getOrderSyncUncomplitedIds.scroll: %s", method, e.toString()), e);
            } finally {
                if (sr != null) {
                    try {
                        sr.close();
                    } catch (Exception ex) {
                    }
                }
            }

            if (orderHistoryIds != null && !orderHistoryIds.isEmpty()) {
                try {
                    removeOrderUncompleted(schedulerId, triggerIds);
                } catch (Exception e) {
                    throw new Exception(String.format("%s: error on removeOrderUncompleted: %s", method, e.toString()), e);
                }
                try {
                    int size = orderHistoryIds.size();
                    if (size > SOSHibernateSession.LIMIT_IN_CLAUSE) {
                        int counterTotal = 0;
                        int counterSkip = 0;
                        int counterTriggers = 0;
                        int counterExecutions = 0;
                        int counterOrderExecutions = 0;
                        int counterStandaloneExecutions = 0;

                        for (int i = 0; i < size; i += SOSHibernateSession.LIMIT_IN_CLAUSE) {
                            List<Long> subList;
                            if (size > i + SOSHibernateSession.LIMIT_IN_CLAUSE) {
                                subList = orderHistoryIds.subList(i, (i + SOSHibernateSession.LIMIT_IN_CLAUSE));
                            } else {
                                subList = orderHistoryIds.subList(i, size);
                            }
                            Criteria cr = getDbLayer().getSchedulerHistoryOrderSteps(schedulerSession, largeResultFetchSizeScheduler, schedulerId,
                                    null, null, subList, null);
                            CounterSynchronize counter = synchronizeOrderHistory(cr, dateToAsMinutes);
                            counterTotal += counter.getTotal();
                            counterSkip += counter.getSkip();
                            counterTriggers += counter.getTriggers();
                            counterExecutions += counter.getExecutions();
                            counterOrderExecutions += counter.getOrderExecutions();
                            counterStandaloneExecutions += counter.getStandaloneExecutions();

                        }
                        counterOrderSyncUncompleted.setTotal(counterTotal);
                        counterOrderSyncUncompleted.setSkip(counterSkip);
                        counterOrderSyncUncompleted.setTriggers(counterTriggers);
                        counterOrderSyncUncompleted.setExecutions(counterExecutions);
                        counterOrderSyncUncompleted.setOrderExecutions(counterOrderExecutions);
                        counterOrderSyncUncompleted.setStandaloneExecutions(counterStandaloneExecutions);
                    } else {
                        Criteria cr = getDbLayer().getSchedulerHistoryOrderSteps(schedulerSession, largeResultFetchSizeScheduler, schedulerId, null,
                                null, orderHistoryIds, null);
                        counterOrderSyncUncompleted = synchronizeOrderHistory(cr, dateToAsMinutes);
                    }
                } catch (Exception e) {
                    throw new Exception(String.format("%s: error on synchronizeOrderHistory: %s", method, e.toString()), e);
                }
            }
        }
    }

    private void synchronizeStandaloneUncompleted(String schedulerId, Long dateToAsMinutes) throws Exception {
        String method = "synchronizeStandaloneUncompleted";
        LOGGER.debug(String.format("%s", method));
        if (schedulerId != null && !schedulerId.isEmpty()) {
            ArrayList<Long> executionIds = new ArrayList<Long>();
            ArrayList<Long> taskHistoryIds = new ArrayList<Long>();
            ScrollableResults sr = null;
            try {
                Criteria cr = getDbLayer().getStandaloneSyncUncomplitedIds(largeResultFetchSizeReporting, schedulerId);
                sr = cr.scroll(ScrollMode.FORWARD_ONLY);
                int i = 0;
                while (sr.next()) {
                    i++;
                    if (i <= SOSHibernateSession.LIMIT_IN_CLAUSE) {
                        executionIds.add((Long) sr.get(0));
                    } else {
                        executionIds = null;
                    }
                    Long taskHistoryId = (Long) sr.get(1);
                    if (!taskHistoryIds.contains(taskHistoryId)) {
                        taskHistoryIds.add(taskHistoryId);
                    }
                }
                sr.close();
                sr = null;
            } catch (Exception e) {
                throw new Exception(String.format("%s: error on getStandaloneSyncUncomplitedIds.scroll: %s", method, e.toString()), e);
            } finally {
                if (sr != null) {
                    try {
                        sr.close();
                    } catch (Exception ex) {
                    }
                }
            }

            if (taskHistoryIds != null && !taskHistoryIds.isEmpty()) {
                try {
                    removeStandaloneUncompleted(schedulerId, executionIds);
                } catch (Exception e) {
                    throw new Exception(String.format("%s: error on removeStandaloneUncompleted: %s", method, e.toString()), e);
                }
                try {
                    LOGGER.debug(String.format("%s: taskHistoryIds.size = %s", method, taskHistoryIds.size()));

                    Criteria cr = getDbLayer().getSchedulerHistoryTasks(schedulerSession, largeResultFetchSizeScheduler, schedulerId, null, null,
                            null, taskHistoryIds);
                    counterStandaloneSyncUncompleted = synchronizeStandaloneHistory(cr, schedulerId, dateToAsMinutes, new ArrayList<Long>());
                } catch (Exception e) {
                    throw new Exception(String.format("%s: error on synchronizeStandaloneHistory: %s", method, e.toString()), e);
                }
            }
        }

    }

    private void synchronizeOrder(String schedulerId, Date dateFrom, Date dateTo, Long dateToAsMinutes) throws Exception {
        String method = "synchronizeOrder";
        try {
            LOGGER.debug(String.format("%s: schedulerId = %s, dateFrom = %s, dateTo = %s", method, schedulerId, ReportUtil.getDateAsString(dateFrom),
                    ReportUtil.getDateAsString(dateTo)));

            Criteria cr = getDbLayer().getSchedulerHistoryOrderSteps(schedulerSession, largeResultFetchSizeScheduler, schedulerId, dateFrom, dateTo,
                    null, null);
            counterOrderSync = synchronizeOrderHistory(cr, dateToAsMinutes);
        } catch (Exception ex) {
            throw new Exception(String.format("%s: %s", method, ex.toString()), ex);
        }
    }

    private void synchronizeStandalone(String schedulerId, Date dateFrom, Date dateTo, Long dateToAsMinutes, ArrayList<Long> excludedTaskIds)
            throws Exception {
        String method = "synchronizeStandalone";
        try {
            LOGGER.debug(String.format("%s: schedulerId = %s, dateFrom = %s, dateTo = %s, excludedTaskIds.size() = %s", method, schedulerId,
                    ReportUtil.getDateAsString(dateFrom), ReportUtil.getDateAsString(dateTo), excludedTaskIds.size()));

            List<Long> ex = new ArrayList<Long>();
            if (excludedTaskIds.size() > 1000) {
                ex = excludedTaskIds.subList(0, 1000);
            } else {
                ex = excludedTaskIds;
            }

            Criteria cr = getDbLayer().getSchedulerHistoryTasks(schedulerSession, largeResultFetchSizeScheduler, schedulerId, dateFrom, dateTo, ex,
                    null);
            counterStandaloneSync = synchronizeStandaloneHistory(cr, schedulerId, dateToAsMinutes, excludedTaskIds);
        } catch (Exception ex) {
            throw new Exception(String.format("%s: %s", method, ex.toString()), ex);
        }
    }

    private boolean calculateIsSyncCompleted(Date startTime, Date endTime, Long dateToAsMinutes) {
        boolean syncCompleted = false;
        if (endTime == null) {
            if (maxUncompletedAge > 0) {
                Long startTimeMinutes = startTime.getTime() / 1000 / 60;
                Long diffMinutes = dateToAsMinutes - startTimeMinutes;
                if (diffMinutes > maxUncompletedAge) {
                    syncCompleted = true;
                }
            }
        } else {
            syncCompleted = true;
        }
        return syncCompleted;
    }

    private synchronized CounterSynchronize synchronizeStandaloneHistory(Criteria criteria, String schedulerId, Long dateToAsMinutes,
            ArrayList<Long> excludedTaskIds) throws Exception {
        String method = "synchronizeStandaloneHistory";
        CounterSynchronize counter = new CounterSynchronize();
        try {
            LOGGER.debug(String.format("%s", method));
            DateTime start = new DateTime();
            HashMap<Long, Long> insertedTriggers = new HashMap<Long, Long>();
            HashMap<Long, DBItemReportTrigger> insertedTriggerObjects = new HashMap<Long, DBItemReportTrigger>();
            int countTotal = 0;
            int countSkip = 0;
            int countTriggers = 0;
            int countExecutions = 0;
            int countStandaloneExecutions = 0;
            int countOrderExecutions = 0;
            List<DBItemSchedulerHistory> result = null;
            try {
                schedulerSession.beginTransaction();
                result = getDbLayer().executeCriteriaList(criteria);
                schedulerSession.commit();
            } catch (Exception e) {
                try {
                    schedulerSession.rollback();
                } catch (Exception ex) {
                    LOGGER.warn(String.format("%s: schedulerConnection %s", method, ex.toString()), ex);
                }
                throw new Exception(String.format("error on executeCriteriaList: %s", e.toString()), e);
            }

            getDbLayer().getSession().beginTransaction();
            int totalSize = result.size();
            for (int i = 0; i < totalSize; i++) {

                countTotal++;
                DBItemSchedulerHistory task = result.get(i);
                if (task.getJobName().equals("(Spooler)")) {
                    LOGGER.debug(String.format("%s: %s) skip jobName = %s", method, countTotal, task.getJobName()));
                    countSkip++;
                    continue;
                }
                if (excludedTaskIds.contains(task.getId())) {
                    LOGGER.debug(String.format("%s: %s) skip jobName = %s", method, countTotal, task.getJobName()));
                    countSkip++;
                    continue;
                }

                Long triggerId = new Long(0);
                Long step = new Long(1);
                String state = null;
                Date startTime = task.getStartTime();
                Date endTime = task.getEndTime();
                boolean isError = task.isError();
                String errorCode = task.getErrorCode();
                String errorText = task.getErrorText();
                boolean syncCompleted = calculateIsSyncCompleted(task.getStartTime(), task.getEndTime(), dateToAsMinutes);

                String cause = task.getCause() == null ? "standalone" : task.getCause();

                if (cause.equals("order")) {
                    ArrayList<Long> taskHistoryIds = new ArrayList<Long>();
                    taskHistoryIds.add(task.getId());

                    List<DBItemSchedulerHistoryOrderStepReporting> orderSteps = null;
                    try {
                        schedulerSession.beginTransaction();
                        Criteria criteriaOrderSteps = getDbLayer().getSchedulerHistoryOrderSteps(schedulerSession, largeResultFetchSizeScheduler,
                                schedulerId, null, null, null, taskHistoryIds);
                        orderSteps = getDbLayer().executeCriteriaList(criteriaOrderSteps);
                        schedulerSession.commit();
                    } catch (Exception e) {
                        try {
                            schedulerSession.rollback();
                        } catch (Exception ex) {
                            LOGGER.warn(String.format("%s: schedulerConnection %s", method, ex.toString()), ex);
                        }
                        throw new Exception(String.format("error on getSchedulerHistoryOrderSteps: %s", e.toString()), e);
                    }

                    if (orderSteps != null && orderSteps.size() > 0) {
                        DBItemSchedulerHistoryOrderStepReporting orderStep = orderSteps.get(0);

                        if (insertedTriggers.containsKey(orderStep.getOrderHistoryId())) {
                            triggerId = insertedTriggers.get(orderStep.getOrderHistoryId());

                            LOGGER.debug(String.format("%s: %s) use triggerId=%s", method, countTotal, triggerId));
                        } else {
                            DBItemReportTrigger rt = null;
                            try {
                                rt = getDbLayer().getTrigger(orderStep.getOrderSchedulerId(), orderStep.getOrderHistoryId());
                            } catch (Exception e) {
                                throw new Exception(String.format("error on getTrigger: %s", e.toString()), e);
                            }

                            if (rt == null) {
                                boolean triggerSyncCompleted = calculateIsSyncCompleted(orderStep.getOrderStartTime(), orderStep.getOrderEndTime(),
                                        dateToAsMinutes);

                                List<Object[]> triggerInfos = null;
                                try {
                                    triggerInfos = getDbLayer().getInventoryInfoForTrigger(largeResultFetchSizeReporting, orderStep
                                            .getOrderSchedulerId(), options.current_scheduler_hostname.getValue(), options.current_scheduler_http_port
                                                    .value(), orderStep.getOrderId(), orderStep.getOrderJobChain());
                                } catch (Exception e) {
                                    throw new Exception(String.format("error on getInventoryInfoForTrigger: %s", e.toString()), e);
                                }

                                DBItemReportInventoryInfo tii = getInventoryInfo(triggerInfos);
                                LOGGER.debug(String.format(
                                        "%s: %s) getInventoryInfoForTrigger(orderId=%s, jobChain=%s): tii.getTitle=%s, tii.getIsRuntimeDefined=%s",
                                        method, countTotal, orderStep.getOrderId(), orderStep.getOrderJobChain(), tii.getTitle(), tii
                                                .getIsRuntimeDefined()));

                                TriggerResult tr = getTriggerResult(orderStep.getOrderSchedulerId(), orderStep.getOrderHistoryId(), ReportUtil
                                        .normalizeDbItemPath(orderStep.getOrderJobChain()), orderStep.getTaskCause());
                                if (tr == null) {
                                    continue;
                                }

                                rt = getDbLayer().createReportTrigger(orderStep.getOrderSchedulerId(), orderStep.getOrderHistoryId(), orderStep
                                        .getOrderId(), orderStep.getOrderTitle(), ReportUtil.getFolderFromName(orderStep.getOrderJobChain()),
                                        orderStep.getOrderJobChain(), ReportUtil.getBasenameFromName(orderStep.getOrderJobChain()), tii.getTitle(),
                                        orderStep.getOrderState(), orderStep.getOrderStateText(), orderStep.getOrderStartTime(), orderStep
                                                .getOrderEndTime(), triggerSyncCompleted, tii.getIsRuntimeDefined(), tr.getStartCause(), tr
                                                        .getSteps(), tr.isError(), tr.getErrorCode(), tr.getErrorText());
                                countTriggers++;

                                LOGGER.debug(String.format("%s: %s) trigger (%s) inserted for taskId = %s", method, countTotal, rt.getId(), task
                                        .getId()));

                                if (this.notificationPlugin != null) {
                                    insertedTriggerObjects.put(triggerId, rt);
                                }
                            }
                            triggerId = rt.getId();
                            insertedTriggers.put(orderStep.getOrderHistoryId(), triggerId);
                        }
                        step = orderStep.getStepStep();
                        startTime = orderStep.getStepStartTime();
                        endTime = orderStep.getStepEndTime();
                        state = orderStep.getStepState();
                        isError = orderStep.isStepError();
                        errorCode = orderStep.getStepErrorCode();
                        errorText = orderStep.getStepErrorText();
                        syncCompleted = endTime != null;

                        LOGGER.debug(String.format(
                                "%s: %s) schedulerId = %s, orderHistoryId = %s, jobChain = %s, order id = %s, step = %s, step state = %s", method,
                                countTotal, orderStep.getOrderSchedulerId(), orderStep.getOrderHistoryId(), orderStep.getOrderJobChain(), orderStep
                                        .getOrderId(), orderStep.getStepStep(), orderStep.getStepState()));
                    }
                }

                DBItemReportExecution re = null;
                try {
                    re = getDbLayer().getExecution(schedulerId, task.getId(), triggerId, step);
                } catch (Exception e) {
                    throw new Exception(String.format("error on getExecution: %s", e.toString()), e);
                }
                if (re == null) {
                    LOGGER.debug(String.format(
                            "%s: %s) insert: schedulerId = %s, taskHistoryId = %s, triggerId = %s, step = %s, jobName = %s, cause = %s, syncCompleted = %s",
                            method, countTotal, task.getSpoolerId(), task.getId(), triggerId, step, task.getJobName(), cause, syncCompleted));

                    List<Object[]> executionInfos = null;
                    try {
                        executionInfos = getDbLayer().getInventoryInfoForExecution(largeResultFetchSizeReporting, task.getSpoolerId(),
                                options.current_scheduler_hostname.getValue(), options.current_scheduler_http_port.value(), task.getJobName(), false);
                    } catch (Exception e) {
                        throw new Exception(String.format("error on getInventoryInfoForExecution: %s", e.toString()), e);
                    }
                    DBItemReportInventoryInfo eii = getInventoryInfo(executionInfos);
                    LOGGER.debug(String.format("%s: %s) getInventoryInfoForExecution(jobName=%s): eii.getTitle=%s, eii.getIsRuntimeDefined=%s",
                            method, countTotal, task.getJobName(), eii.getTitle(), eii.getIsRuntimeDefined()));

                    re = getDbLayer().createReportExecution(task.getSpoolerId(), task.getId(), triggerId, task.getClusterMemberId(), task.getSteps(),
                            step, ReportUtil.getFolderFromName(task.getJobName()), task.getJobName(), ReportUtil.getBasenameFromName(task
                                    .getJobName()), eii.getTitle(), startTime, endTime, state, cause, task.getExitCode(), isError, errorCode,
                            errorText, task.getAgentUrl(), syncCompleted, eii.getIsRuntimeDefined());

                    try {
                        getDbLayer().getSession().save(re);
                    } catch (Exception e) {
                        throw new Exception(String.format("error on execution save: %s", e.toString()), e);
                    }

                    countExecutions++;
                    if (cause.equals("order")) {
                        countOrderExecutions++;
                    } else {
                        countStandaloneExecutions++;
                    }

                    re.setTaskStartTime(task.getStartTime());
                    re.setTaskEndTime(task.getEndTime());

                    if (re.getTriggerId() > new Long(0)) {
                        pluginOnProcess(insertedTriggerObjects, re);
                    }

                } else {
                    countSkip++;
                    LOGGER.debug(String.format(
                            "%s: %s) skip (already exist): schedulerId = %s, taskHistoryId = %s, triggerId = %s, step = %s, jobName = %s, cause = %s, syncCompleted = %s",
                            method, countTotal, task.getSpoolerId(), task.getId(), triggerId, step, task.getJobName(), cause, syncCompleted));
                }

                if (countTotal % options.log_info_step.value() == 0) {
                    LOGGER.info(String.format("%s: %s of %s history steps processed ...", method, countTotal, totalSize));
                }
            }
            getDbLayer().getSession().commit();
            LOGGER.debug(String.format("%s: duration = %s", method, ReportUtil.getDuration(start, new DateTime())));

            counter.setTotal(countTotal);
            counter.setSkip(countSkip);
            counter.setExecutions(countExecutions);
            counter.setStandaloneExecutions(countStandaloneExecutions);
            counter.setOrderExecutions(countOrderExecutions);
            counter.setTriggers(countTriggers);
            LOGGER.debug(String.format(
                    "%s: total history steps = %s, standalone executions = %s, order executions = %s, executions total = %s, triggers = %s, skip = %s ",
                    method, counter.getTotal(), counter.getStandaloneExecutions(), counter.getOrderExecutions(), counter.getExecutions(), counter
                            .getTriggers(), counter.getSkip()));

        } catch (Exception e) {
            try {
                getDbLayer().getSession().rollback();
            } catch (Exception ex) {
                LOGGER.warn(String.format("%s: %s", method, ex.toString()), ex);
            }
            throw new Exception(String.format("%s: %s", method, e.toString()), e);
        }
        return counter;
    }

    private synchronized CounterSynchronize synchronizeOrderHistory(Criteria criteria, Long dateToAsMinutes) throws Exception {
        String method = "synchronizeOrderHistory";
        CounterSynchronize counter = new CounterSynchronize();
        try {
            LOGGER.debug(String.format("%s", method));
            DateTime start = new DateTime();
            HashMap<Long, Long> inserted = new HashMap<Long, Long>();
            HashMap<Long, DBItemReportTrigger> insertedTriggerObjects = new HashMap<Long, DBItemReportTrigger>();
            int countTotal = 0;
            int countSkip = 0;
            int countTriggers = 0;
            int countExecutions = 0;
            List<DBItemSchedulerHistoryOrderStepReporting> result = null;
            try {
                schedulerSession.beginTransaction();
                result = getDbLayer().executeCriteriaList(criteria); // criteria.list();
                schedulerSession.commit();
            } catch (Exception e) {
                try {
                    schedulerSession.rollback();
                } catch (Exception ex) {
                    LOGGER.warn(String.format("%s: schedulerConnection %s", method, ex.toString()), ex);
                }
                throw new Exception(String.format("error on executeCriteriaList: %s", e.toString()), e);
            }

            getDbLayer().getSession().beginTransaction();
            int totalSize = result.size();
            for (int i = 0; i < totalSize; i++) {
                countTotal++;
                DBItemSchedulerHistoryOrderStepReporting step = result.get(i);
                if (step.getOrderHistoryId() == null && step.getOrderId() == null && step.getOrderStartTime() == null) {
                    countSkip++;
                    LOGGER.debug(String.format("%s: %s) order object is null. step = %s, historyId = %s ", method, countTotal, step.getStepState(),
                            step.getStepHistoryId()));
                    continue;
                }
                if (step.getTaskId() == null && step.getTaskJobName() == null && step.getTaskCause() == null) {
                    LOGGER.debug(String.format("%s: %s) task object is null. jobChain = %s, order = %s, step = %s, taskId = %s ", method, countTotal,
                            step.getOrderJobChain(), step.getOrderId(), step.getStepState(), step.getStepTaskId()));
                    
                    step.setTaskId(step.getStepTaskId());
                    step.setTaskCause("order");
                    String jobName = getDbLayer().getInventoryJobName(step.getOrderSchedulerId(), options.current_scheduler_hostname.getValue(), options.current_scheduler_http_port.value(), 
                            step.getOrderJobChain(), step.getStepState());
                    step.setTaskJobName(jobName == null ? "inventoryNotFoundJob" : jobName);
                }

                if (!synchronizedOrderTaskIds.contains(step.getTaskId())) {
                    synchronizedOrderTaskIds.add(step.getTaskId());
                }

                LOGGER.debug(String.format("%s: %s) schedulerId = %s, orderHistoryId = %s, jobChain = %s, order id = %s, step = %s, step state = %s",
                        method, countTotal, step.getOrderSchedulerId(), step.getOrderHistoryId(), step.getOrderJobChain(), step.getOrderId(), step
                                .getStepStep(), step.getStepState()));
                Long triggerId = new Long(0);
                if (inserted.containsKey(step.getOrderHistoryId())) {
                    triggerId = inserted.get(step.getOrderHistoryId());

                    LOGGER.debug(String.format("%s: %s) use triggerId=%s", method, countTotal, triggerId));

                } else {
                    List<Object[]> triggerInfos = null;
                    try {
                        triggerInfos = getDbLayer().getInventoryInfoForTrigger(largeResultFetchSizeReporting, step.getOrderSchedulerId(),
                                options.current_scheduler_hostname.getValue(), options.current_scheduler_http_port.value(), step.getOrderId(), step
                                        .getOrderJobChain());
                    } catch (Exception e) {
                        throw new Exception(String.format("error on getInventoryInfoForTrigger: %s", e.toString()), e);
                    }
                    DBItemReportInventoryInfo ii = getInventoryInfo(triggerInfos);
                    LOGGER.debug(String.format(
                            "%s: %s) getInventoryInfoForTrigger(orderId=%s, jobChain=%s): ii.getTitle=%s, ii.getIsRuntimeDefined=%s", method,
                            countTotal, step.getOrderId(), step.getOrderJobChain(), ii.getTitle(), ii.getIsRuntimeDefined()));

                    TriggerResult tr = getTriggerResult(step.getOrderSchedulerId(), step.getOrderHistoryId(), ReportUtil.normalizeDbItemPath(step
                            .getOrderJobChain()), step.getTaskCause());
                    if (tr == null) {
                        continue;
                    }

                    boolean syncCompleted = calculateIsSyncCompleted(step.getOrderStartTime(), step.getOrderEndTime(), dateToAsMinutes);
                    DBItemReportTrigger rt = getDbLayer().createReportTrigger(step.getOrderSchedulerId(), step.getOrderHistoryId(), step.getOrderId(),
                            step.getOrderTitle(), ReportUtil.getFolderFromName(step.getOrderJobChain()), step.getOrderJobChain(), ReportUtil
                                    .getBasenameFromName(step.getOrderJobChain()), ii.getTitle(), step.getOrderState(), step.getOrderStateText(), step
                                            .getOrderStartTime(), step.getOrderEndTime(), syncCompleted, ii.getIsRuntimeDefined(), tr.getStartCause(),
                            tr.getSteps(), tr.isError(), tr.getErrorCode(), tr.getErrorText());
                    countTriggers++;
                    triggerId = rt.getId();
                    inserted.put(step.getOrderHistoryId(), triggerId);
                    LOGGER.debug(String.format("%s: %s) trigger created rt.getId = %s", method, countTotal, rt.getId()));

                    if (this.notificationPlugin != null) {
                        insertedTriggerObjects.put(triggerId, rt);
                    }
                }

                List<Object[]> executionInfos = null;
                try {
                    executionInfos = getDbLayer().getInventoryInfoForExecution(largeResultFetchSizeReporting, step.getOrderSchedulerId(),
                            options.current_scheduler_hostname.getValue(), options.current_scheduler_http_port.value(), step.getTaskJobName(), false);
                } catch (Exception e) {
                    throw new Exception(String.format("error on getInventoryInfoForExecution: %s", e.toString()), e);
                }
                DBItemReportInventoryInfo eii = getInventoryInfo(executionInfos);
                LOGGER.debug(String.format("%s: %s) getInventoryInfoForExecution(jobName=%s): eii.getTitle=%s, eii.getIsRuntimeDefined=%s", method,
                        countTotal, step.getTaskJobName(), eii.getTitle(), eii.getIsRuntimeDefined()));

                DBItemReportExecution re = getDbLayer().createReportExecution(step.getOrderSchedulerId(), step.getTaskId(), triggerId, step
                        .getTaskClusterMemberId(), step.getTaskSteps(), step.getStepStep(), ReportUtil.getFolderFromName(step.getTaskJobName()), step
                                .getTaskJobName(), ReportUtil.getBasenameFromName(step.getTaskJobName()), eii.getTitle(), step.getStepStartTime(),
                        step.getStepEndTime(), step.getStepState(), step.getTaskCause(), step.getTaskExitCode(), step.isStepError(), step
                                .getStepErrorCode(), step.getStepErrorText(), step.getAgentUrl(), step.getStepEndTime() != null, eii
                                        .getIsRuntimeDefined());

                LOGGER.debug(String.format("%s: %s) save execution for triggerId = %s", method, countTotal, triggerId));

                try {
                    getDbLayer().getSession().save(re);
                } catch (Exception e) {
                    throw new Exception(String.format("error on execution save: %s", e.toString()), e);
                }
                re.setTaskStartTime(step.getTaskStartTime());
                re.setTaskEndTime(step.getTaskEndTime());
                countExecutions++;

                pluginOnProcess(insertedTriggerObjects, re);

                if (countTotal % options.log_info_step.value() == 0) {
                    LOGGER.info(String.format("%s: %s of %s history steps processed ...", method, countTotal, totalSize));
                }
            }
            // if(!getDbLayer().getSession().getTransaction().getStatus().equals(TransactionStatus.ACTIVE) ) {
            getDbLayer().getSession().commit();
            // }
            LOGGER.debug(String.format("%s: duration = %s", method, ReportUtil.getDuration(start, new DateTime())));

            counter.setTotal(countTotal);
            counter.setSkip(countSkip);
            counter.setTriggers(countTriggers);
            counter.setExecutions(countExecutions);

            LOGGER.debug(String.format("%s: total = %s, triggers = %s, executions = %s, skip = %s ", method, counter.getTotal(), counter
                    .getTriggers(), counter.getExecutions(), counter.getSkip()));
        } catch (Exception e) {
            try {
                getDbLayer().getSession().rollback();
            } catch (Exception ex) {
                LOGGER.warn(String.format("%s: reportingConnection %s", method, ex.toString()), ex);
            }
            throw new Exception(String.format("%s: %s", method, e.toString()), e);
        }
        return counter;
    }

    private TriggerResult getTriggerResult(String schedulerId, Long historyId, String parentName, String startCause) throws Exception {
        String method = "getTriggerResult";
        if (startCause.equals(EStartCauses.ORDER.value())) {
            String jcStartCause = getDbLayer().getInventoryJobChainStartCause(schedulerId, this.options.current_scheduler_hostname.getValue(),
                    this.options.current_scheduler_http_port.value(), parentName);
            if (!SOSString.isEmpty(jcStartCause)) {
                startCause = jcStartCause;
            }
        }

        TriggerResult result = null;
        DBItemSchedulerOrderStepHistory lastStep = null;
        try {
            schedulerSession.beginTransaction();
            lastStep = getDbLayer().getSchedulerOrderHistoryLastStep(schedulerSession, historyId);
            schedulerSession.commit();
        } catch (Exception e) {
            try {
                schedulerSession.rollback();
            } catch (Exception ex) {
                LOGGER.warn(String.format("%s: schedulerConnection %s", method, ex.toString()), ex);
            }
            throw new Exception(String.format("%s: error on getSchedulerOrderHistoryLastStep: %s", method, e.toString()), e);
        }
        if (lastStep != null) {
            if (lastStep.getId() == null) {
                throw new Exception(String.format("%s: lastStep.id for historyId=%s is null", method, historyId));
            }
            result = new TriggerResult();
            result.setStartCause(startCause);
            result.setSteps(lastStep.getId().getStep());
            result.setError(lastStep.isError() == null ? false : lastStep.isError().booleanValue());
            result.setErrorCode(lastStep.getErrorCode());
            result.setErrorText(lastStep.getErrorText());
        } else {
            LOGGER.debug(String.format("%s: not create DBItemReportTriggerResult. last step not found", method));
        }
        return result;
    }

    private DBItemReportInventoryInfo getInventoryInfo(List<Object[]> infos) {
        DBItemReportInventoryInfo item = new DBItemReportInventoryInfo();
        item.setTitle(null);
        item.setIsRuntimeDefined(false);
        if (infos != null && infos.size() > 0) {
            try {
                Object[] row = infos.get(0);
                item.setTitle((String) row[0]);
                item.setIsRuntimeDefined((row[1] + "").equals("1"));
            } catch (Exception ex) {
                LOGGER.warn(String.format("can't create DBItemReportInventoryInfo object: %s", ex.toString()));
            }
        }
        return item;
    }

    private void initCounters() throws Exception {
        counterOrderRemoved = new CounterRemove();
        counterOrderUncompletedRemoved = new CounterRemove();
        counterOrderSync = new CounterSynchronize();
        counterOrderSyncUncompleted = new CounterSynchronize();

        counterStandaloneRemoved = new CounterRemove();
        counterStandaloneUncompletedRemoved = new CounterRemove();
        counterStandaloneSync = new CounterSynchronize();
        counterStandaloneSyncUncompleted = new CounterSynchronize();
    }

    private void setChangedSummary() throws Exception {
        isChanged = false;
        isOrderChanged = false;
        isStandaloneChanged = false;
        isOrderChangedOnStandalone = false;

        if (counterOrderSync.getTriggers() > 0 || counterOrderSync.getExecutions() > 0 || counterOrderSyncUncompleted.getTriggers() > 0
                || counterOrderSyncUncompleted.getExecutions() > 0) {
            isOrderChanged = true;
            isChanged = true;
        }
        if (counterStandaloneSync.getTriggers() > 0 || counterStandaloneSync.getOrderExecutions() > 0 || counterStandaloneSyncUncompleted
                .getTriggers() > 0 || counterStandaloneSyncUncompleted.getOrderExecutions() > 0) {
            isOrderChanged = true;
            isChanged = true;
            isOrderChangedOnStandalone = true;
        }
        if (counterStandaloneSync.getStandaloneExecutions() > 0 || counterStandaloneSyncUncompleted.getStandaloneExecutions() > 0) {
            isStandaloneChanged = true;
            isChanged = true;
        }
    }

    private void logSummary(Date dateFrom, Date dateTo, DateTime start) throws Exception {
        String method = "logSummary";
        String from = ReportUtil.getDateAsString(dateFrom);
        String to = ReportUtil.getDateAsString(dateTo);

        if (isChanged) {
            // Order
            String range = "order";
            LOGGER.debug(String.format("[%s to %s UTC][%s][removed]triggers=%s, executions=%s", from, to, range, counterOrderRemoved.getTriggers(),
                    counterOrderRemoved.getExecutions()));
            LOGGER.debug(String.format("[%s to %s UTC][%s][removed trigger dates=%s, execution dates=%s", from, to, range, counterOrderRemoved
                    .getTriggerDates(), counterOrderRemoved.getExecutionDates()));

            if (isOrderChanged) {
                LOGGER.info(String.format(
                        "[%s to %s UTC][%s][new]history steps=%s, triggers=%s, executions=%s, skip=%s [old]total=%s, triggers=%s, executions=%s, skip=%s",
                        from, to, range, counterOrderSync.getTotal(), counterOrderSync.getTriggers(), counterOrderSync.getExecutions(),
                        counterOrderSync.getSkip(), counterOrderSyncUncompleted.getTotal(), counterOrderSyncUncompleted.getTriggers(),
                        counterOrderSyncUncompleted.getExecutions(), counterOrderSyncUncompleted.getSkip()));
            } else {
                LOGGER.info(String.format("[%s to %s UTC][%s] 0 changes", from, to, range));
            }

            // Standalone
            range = "standalone";
            LOGGER.debug(String.format("[%s to %s UTC][%s][removed]executions=%s, execution dates=%s", from, to, range, counterStandaloneRemoved
                    .getExecutions(), counterStandaloneRemoved.getExecutionDates()));
            LOGGER.debug(String.format("[%s to %s UTC][%s][removed old uncompleted]executions=%s, execution dates=%s", from, to, range,
                    counterStandaloneUncompletedRemoved.getExecutions(), counterStandaloneUncompletedRemoved.getExecutionDates()));

            if (isStandaloneChanged || isOrderChangedOnStandalone) {
                LOGGER.info(String.format(
                        "[%s to %s UTC][%s][new]history tasks=%s, standalone executions = %s, order executions = %s, total executions=%s, triggers=%s, skip=%s [old]total=%s, executions=%s, triggers=%s, skip=%s",
                        from, to, range, counterStandaloneSync.getTotal(), counterStandaloneSync.getStandaloneExecutions(), counterStandaloneSync
                                .getOrderExecutions(), counterStandaloneSync.getExecutions(), counterStandaloneSync.getTriggers(),
                        counterStandaloneSync.getSkip(), counterStandaloneSyncUncompleted.getTotal(), counterStandaloneSyncUncompleted
                                .getExecutions(), counterStandaloneSyncUncompleted.getTriggers(), counterStandaloneSyncUncompleted.getSkip()));
            } else {
                LOGGER.info(String.format("[%s to %s UTC][%s] 0 changes", from, to, range));
            }
        } else {
            LOGGER.info(String.format("[%s to %s UTC] 0 changes", from, to));
        }
        LOGGER.debug(String.format("%s: duration = %s", method, ReportUtil.getDuration(start, new DateTime())));
    }

    private Date getDateFrom(DBItemReportVariable reportingVariable, Date dateTo) throws Exception {
        String method = "getDateFrom";
        Long currentMaxAge = new Long(maxHistoryAge);
        Long storedMaxAge = reportingVariable.getNumericValue();
        Date storedDateFrom = SOSString.isEmpty(reportingVariable.getTextValue()) ? null : ReportUtil.getDateFromString(reportingVariable
                .getTextValue());
        Date dateFrom = null;
        LOGGER.debug(String.format("%s: storedDateFrom=%s, storedMaxAge=%s, currentMaxAge=%s", method, ReportUtil.getDateAsString(storedDateFrom),
                storedMaxAge, currentMaxAge));

        if (storedDateFrom == null) {// initial value
            dateFrom = ReportUtil.getDateTimeMinusMinutes(dateTo, currentMaxAge);
            LOGGER.debug(String.format("%s: dateFrom=%s (initial, %s-%s)", method, ReportUtil.getDateAsString(dateFrom), ReportUtil.getDateAsString(
                    dateTo), currentMaxAge));
        } else {
            if (options.force_max_history_age.value()) {
                LOGGER.debug(String.format("%s: dateFrom=null (force_max_history_age=true)", method));
                dateFrom = null;
            } else {
                Long startTimeMinutes = storedDateFrom.getTime() / 1000 / 60;
                Long endTimeMinutes = dateTo.getTime() / 1000 / 60;
                Long diffMinutes = endTimeMinutes - startTimeMinutes;
                if (diffMinutes > currentMaxAge) {
                    Long countHistoryTasks = getDbLayer().getCountSchedulerHistoryTasks(schedulerSession, options.current_scheduler_id.getValue(),
                            storedDateFrom);
                    if (countHistoryTasks > maxHistoryTasks) {
                        dateFrom = null;
                        LOGGER.info(String.format("%s: dateFrom=null (%s > %s, countHistoryTasks %s > %s)", method, diffMinutes, currentMaxAge,
                                countHistoryTasks, maxHistoryTasks));
                    } else {
                        dateFrom = storedDateFrom;
                        LOGGER.debug(String.format("%s: dateFrom=%s (use storedDateFrom because %s > %s, countHistoryTasks %s <= %s)", method,
                                ReportUtil.getDateAsString(dateFrom), diffMinutes, currentMaxAge, countHistoryTasks, maxHistoryTasks));
                    }
                } else {
                    dateFrom = storedDateFrom;
                    LOGGER.debug(String.format("%s: dateFrom=%s (use storedDateFrom because %s < %s)", method, ReportUtil.getDateAsString(dateFrom),
                            diffMinutes, currentMaxAge));
                }
                // dateFrom = storedDateFrom;
            }
            if (dateFrom == null) {
                dateFrom = ReportUtil.getDateTimeMinusMinutes(dateTo, currentMaxAge);
                LOGGER.debug(String.format("%s: dateFrom=%s (%s-%s)", method, ReportUtil.getDateAsString(dateFrom), ReportUtil.getDateAsString(
                        dateTo), currentMaxAge));
            }
        }
        return dateFrom;
    }

    private void registerPlugin() {
        if (this.options.execute_notification_plugin.value()) {
            this.notificationPlugin = new FactNotificationPlugin();
        }
    }

    private void pluginOnInit(PluginMailer mailer, Path configDirectory) {
        if (this.notificationPlugin != null) {
            this.notificationPlugin.init(getDbLayer().getSession(), mailer, configDirectory);
            if (this.notificationPlugin.getHasErrorOnInit()) {
                this.notificationPlugin = null;
            }
        }
    }

    private void pluginOnProcess(HashMap<Long, DBItemReportTrigger> insertedTriggers, DBItemReportExecution re) {
        if (this.notificationPlugin == null || insertedTriggers == null) {
            return;
        }

        if (re.getTriggerId().equals(new Long(0))) {
            return;
        }
        if (insertedTriggers.containsKey(re.getTriggerId())) {
            LOGGER.debug(String.format("pluginOnProcess: triggerId=%s", re.getTriggerId()));

            DBItemReportTrigger rt = insertedTriggers.get(re.getTriggerId());
            NotificationReportExecution item = this.notificationPlugin.convert(rt, re);

            this.notificationPlugin.process(item);
        } else {
            LOGGER.debug(String.format("skip pluginOnProcess: triggerId=%s", re.getTriggerId()));
        }
    }

    private void pluginOnExit() {
        if (this.notificationPlugin != null) {
            this.notificationPlugin = null;
        }
    }

    public CounterSynchronize getCounterOrderSync() {
        return counterOrderSync;
    }

    public CounterSynchronize getCounterOrderSyncUncompleted() {
        return counterOrderSyncUncompleted;
    }

    public CounterSynchronize getCounterStandaloneSync() {
        return counterStandaloneSync;
    }

    public CounterSynchronize getCounterStandaloneSyncUncompleted() {
        return counterStandaloneSyncUncompleted;
    }

    public boolean isChanged() {
        return isChanged;
    }

    public boolean isStandaloneChanged() {
        return isStandaloneChanged;
    }

    public boolean isOrderChanged() {
        return isOrderChanged;
    }
}