package com.sos.jitl.checkhistory.classes;

import java.io.StringReader;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

import com.sos.exception.SOSException;
import com.sos.jitl.checkhistory.historyHelper;
import com.sos.jitl.restclient.JobSchedulerRestApiClient;
import com.sos.scheduler.model.answers.HistoryEntry;
import com.sos.scheduler.model.answers.JobChain.OrderHistory.Order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HistoryWebserviceExecuter {

    private static final String JOB_STRING_FOR_WEBSERVICE = "{'jobschedulerId':'%s','limit':1,'jobs':[{'job':'%s'}],'historyStates':";
    private static final String JOB_CHAIN_ORDER_STRING_FOR_WEBSERVICE = "{'jobschedulerId':'%s','limit':1,'orders':[{'jobChain':'%s','orderId':'%s'}],'historyStates':";
    private static final String JOB_CHAIN_STRING_FOR_WEBSERVICE = "{'jobschedulerId':'%s','limit':1,'orders':[{'jobChain':'%s'}],'historyStates':";

    private static final Logger LOGGER = LoggerFactory.getLogger(HistoryWebserviceExecuter.class);
    private String accessToken = "";
    private JobSchedulerRestApiClient jobSchedulerRestApiClient;
    private historyHelper historyHelper;
    private String jocAccount;
    private String schedulerId;
    private String jocUrl;
    private String timeLimit = "";
    private String jobName;
    private String jobChainName;
    private String orderId;

    public HistoryWebserviceExecuter(String jocUrl, String jocAccount) {
        super();
        historyHelper = new historyHelper();
        jobSchedulerRestApiClient = new JobSchedulerRestApiClient();
        this.jocUrl = jocUrl;
        this.jocAccount = jocAccount;
    }

    public HistoryWebserviceExecuter(String jocUrl) {
        super();
        historyHelper = new historyHelper();
        jobSchedulerRestApiClient = new JobSchedulerRestApiClient();
        this.jocUrl = jocUrl;
    }

    private BigInteger string2BigInteger(String s) {
        try {
            return new BigInteger(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private HistoryEntry json2HistoryEntry(String answer) throws Exception {
        HistoryEntry historyEntry = new HistoryEntry();
        JsonObject history = jsonFromString(answer);
        if (history.getJsonArray("history") != null && history.getJsonArray("history").size() > 0) {
            JsonObject entry = history.getJsonArray("history").getJsonObject(0);
            if (entry != null) {
                JsonObject error = entry.getJsonObject("error");

                if (error != null) {
                    historyEntry.setError(BigInteger.valueOf(1));
                    historyEntry.setErrorCode(error.getString("code", ""));
                    historyEntry.setErrorText(error.getString("message", ""));
                } else {
                    historyEntry.setError(BigInteger.valueOf(0));
                }

                historyEntry.setExitCode(BigInteger.valueOf(entry.getInt("exitCode", 0)));

                historyEntry.setTaskId(string2BigInteger(entry.getString("taskId", "")));
                historyEntry.setId(string2BigInteger(entry.getString("taskId", "")));
                historyEntry.setJobName(entry.getString("job", ""));
                historyEntry.setStartTime(entry.getString("startTime", ""));
                historyEntry.setEndTime(entry.getString("endTime", ""));
            }
        }else {
            if (history.getJsonArray("history") == null  && !history.getBoolean("isPermitted")) {
                throw new Exception("User is not allowed to execute restservice /tasks/history");
            }
        }
        return historyEntry;
    }

    private Order json2JobChainHistoryEntry(String answer) throws Exception {
        Order order = new Order();
        JsonObject history = jsonFromString(answer);
        if (history.getJsonArray("history") != null && history.getJsonArray("history").size() > 0) {
            JsonObject entry = history.getJsonArray("history").getJsonObject(0);
            if (entry != null) {
                order.setId(entry.getString("orderId", ""));
                order.setState(entry.getString("node", ""));
                order.setOrder(entry.getString("orderId", ""));
                order.setHistoryId(string2BigInteger(entry.getString("historyId", "")));
                order.setJobChain(entry.getString("jobChain", ""));
                order.setStartTime(entry.getString("startTime", ""));
                order.setEndTime(entry.getString("endTime", ""));
            }
        }else {
            if (history.getJsonArray("history") == null  && !history.getBoolean("isPermitted")) {
                throw new Exception("User is not allowed to execute restservice /orders/history");
            }
       }
        return order;
    }

    private JsonObject jsonFromString(String jsonObjectStr) {
        JsonReader jsonReader = Json.createReader(new StringReader(jsonObjectStr));
        JsonObject object = jsonReader.readObject();
        jsonReader.close();
        return object;
    }

    public void login() throws SOSException, URISyntaxException {
        jobSchedulerRestApiClient.addHeader("Content-Type", "application/json");
        jobSchedulerRestApiClient.addHeader("Accept", "application/json");
        jobSchedulerRestApiClient.addAuthorizationHeader(jocAccount);

        String answer = jobSchedulerRestApiClient.postRestService(new URI(jocUrl + "/security/login"), "");
        JsonObject login = jsonFromString(answer);
        if (login.get("accessToken") != null) {
            accessToken = login.getString("accessToken");
            jobSchedulerRestApiClient.addHeader("X-Access-Token", accessToken);
        }
    }

    public HistoryEntry getJobHistoryEntry(String state) throws Exception {
        if (accessToken.isEmpty()) {
            throw new Exception("AccessToken is empty. Login not executed");
        }

        String body;
        if (!"".equals(timeLimit)) {
            HistoryInterval historyInterval = historyHelper.getUTCIntervalFromTimeLimit(timeLimit);
            historyInterval.getUtcFrom();
            body = String.format(JOB_STRING_FOR_WEBSERVICE, schedulerId, jobName) + "[" + state + "],'dateFrom':'" + historyInterval.getUtcFrom() + "','dateTo':'" + historyInterval
                    .getUtcTo() + "'}";
        } else {
            body = String.format(JOB_STRING_FOR_WEBSERVICE, schedulerId, jobName) + "[" + state + "]}";
        }

        body = body.replace("'", "\"");

        String answer = jobSchedulerRestApiClient.postRestService(new URI(jocUrl + "/tasks/history"), body);
        HistoryEntry h = json2HistoryEntry(answer);
        if (h.getId() == null) {
            return null;
        } else {
            return json2HistoryEntry(answer);
        }
    }

    public HistoryEntry getLastCompletedSuccessfullJobHistoryEntry() throws Exception {
        return getJobHistoryEntry("'SUCCESSFUL'");
    }

    public HistoryEntry getLastCompletedJobHistoryEntry() throws Exception {
        return getJobHistoryEntry("'SUCCESSFUL','FAILED'");
    }

    public HistoryEntry getLastCompletedWithErrorJobHistoryEntry() throws Exception {
        return getJobHistoryEntry("'FAILED'");
    }

    public HistoryEntry getLastRunningJobHistoryEntry() throws Exception {
        return getJobHistoryEntry("'INCOMPLETE'");
    }

    public Order getJobChainHistoryEntry(String state) throws Exception {
        if (accessToken.isEmpty()) {
            throw new Exception("AccessToken is empty. Login not executed");
        }

        String body;

        if (!"".equals(timeLimit)) {
            HistoryInterval historyInterval = historyHelper.getUTCIntervalFromTimeLimit(timeLimit);
            historyInterval.getUtcFrom();
            if (orderId == null || orderId.isEmpty()) {
                body = String.format(JOB_CHAIN_STRING_FOR_WEBSERVICE, schedulerId, jobChainName) + "[" + state + "],'dateFrom':'" + historyInterval.getUtcFrom() + "','dateTo':'"
                        + historyInterval.getUtcTo() + "'}";
            } else {
                body = String.format(JOB_CHAIN_ORDER_STRING_FOR_WEBSERVICE, schedulerId, jobChainName, orderId) + "[" + state + "],'dateFrom':'" + historyInterval.getUtcFrom()
                        + "','dateTo':'" + historyInterval.getUtcTo() + "'}";
            }
        } else {
            if (orderId == null || orderId.isEmpty()) {
                body = String.format(JOB_CHAIN_STRING_FOR_WEBSERVICE, schedulerId, jobChainName) + "[" + state + "]}";
            } else {
                body = String.format(JOB_CHAIN_ORDER_STRING_FOR_WEBSERVICE, schedulerId, jobChainName, orderId) + "[" + state + "]}";
            }
        }

        body = body.replace("'", "\"");

        String answer = jobSchedulerRestApiClient.postRestService(new URI(jocUrl + "/orders/history"), body);
        Order o = json2JobChainHistoryEntry(answer);
        if (o.getHistoryId() == null) {
            return null;
        } else {
            return o;
        }
    }

    public Order getLastCompletedSuccessfullJobChainHistoryEntry() throws Exception {
        return getJobChainHistoryEntry("'SUCCESSFUL'");
    }

    public Order getLastCompletedJobChainHistoryEntry() throws Exception {
        return getJobChainHistoryEntry("'SUCCESSFUL','FAILED'");
    }

    public Order getLastCompletedWithErrorJobChainHistoryEntry() throws Exception {
        return getJobChainHistoryEntry("'FAILED'");
    }

    public Order getLastRunningJobChainHistoryEntry() throws Exception {
        return getJobChainHistoryEntry("'INCOMPLETE'");
    }

    public void login(String xAccessToken) throws SOSException, URISyntaxException {
        if (xAccessToken != null && !xAccessToken.isEmpty()) {
            jobSchedulerRestApiClient.addHeader("Content-Type", "application/json");
            jobSchedulerRestApiClient.addHeader("Accept", "application/json");

            accessToken = xAccessToken;
            jobSchedulerRestApiClient.addHeader("X-Access-Token", xAccessToken);
        } else {
            login();
        }
    }

    public void setTimeLimit(String timeLimit) {
        this.timeLimit = timeLimit;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getJobName() {
        return jobName;
    }

    public void setSchedulerId(String schedulerId) {
        this.schedulerId = schedulerId;
    }

    public void setJobChainName(String jobChainName) {
        this.jobChainName = jobChainName;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

}
