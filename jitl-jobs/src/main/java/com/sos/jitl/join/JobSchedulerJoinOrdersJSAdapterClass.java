
package com.sos.jitl.join;

import com.sos.jitl.join.JobSchedulerJoinOrders;
import com.sos.jitl.join.JobSchedulerJoinOrdersOptions;
import sos.scheduler.job.JobSchedulerJobAdapter;
import sos.xml.SOSXMLXPath;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sos.JSHelper.Exceptions.JobSchedulerException;

public class JobSchedulerJoinOrdersJSAdapterClass extends JobSchedulerJobAdapter {

    private static final String XML_PATH_PARAM_VALUE = "/spooler/answer/order/payload/params/param[@name='%s']/@value";
    private static final String COMMAND_GET_ORDER = "<show_order job_chain='%s' order='%s' what='payload'></show_order>";
    private static final String COMMAND_SET_SERIALIZED =
            "<modify_order job_chain='%s' order='%s'><params><param name='%s' value='%s'/><param name='%s' value='%s'/></params></modify_order>";
    private static final String JOIN_SERIALIZED_OBJECT_CHECK_PARAM_NAME = "join_serialized_object_check";
    private static final String JOIN_SERIALIZED_OBJECT_PARAM_NAME = "join_serialized_object";
    private static final String JOIN_RESET_LIST = "join_reset_list";
    private static final String RESET_STATE_TEXT = "resetStateText";
    private static final String RESUME_FOR_STATE_TEXT = "<modify_order job_chain='%s' order='%s' suspended='no' state='%s'>"
            + "<params><param name='scheduler_join_state_text' " + "value='%s'></param></params></modify_order>";

    private JobSchedulerJoinOrders jobSchedulerJoinOrders;
    private JobSchedulerJoinOrdersOptions jobSchedulerJoinOrdersOptions;
    private boolean fromSpoolerVariables;

    private static final Logger LOGGER = LoggerFactory.getLogger(JobSchedulerJoinOrdersJSAdapterClass.class);

    @Override
    public boolean spooler_process() throws Exception {
        try {
            super.spooler_process();
            doProcessing();
        } catch (Exception e) {
            throw new JobSchedulerException("Fatal Error:" + e.getMessage(), e);
        }
        return signalSuccess();
    }

    private void suspendOrder() {
        if (!spooler_task.order().suspended()) {
            spooler_task.order().set_state(spooler_task.order().state());
            spooler_task.order().set_suspended(true);
        }
        LOGGER.debug(String.format("Suspending order %s", spooler_task.order().id()));
    }

    private void setRequired() {
        String stateParamName = spooler_task.order().job_chain().name() + "," + this.getCurrentNodeName() + "_required_orders";
        String stateParamValue = spooler_task.order().params().value(stateParamName);
        if (!"".equals(stateParamValue)) {
            jobSchedulerJoinOrdersOptions.required_orders.setValue(stateParamValue);
        } else {
            stateParamName = this.getCurrentNodeName() + "_required_orders";
            stateParamValue = spooler_task.order().params().value(stateParamName);
            if (!"".equals(stateParamValue)) {
                jobSchedulerJoinOrdersOptions.required_orders.setValue(stateParamValue);
            }
        }
    }

    private JoinOrder getJoinOrder() {
        String jobChain = spooler_task.order().job_chain().path();
        String orderId = spooler_task.order().id();
        String joinSessionId = "";
        boolean isMainOrder = !spooler_task.order().end_state().equals(spooler_task.order().state());
        if (jobSchedulerJoinOrdersOptions.joinSessionId.isDirty()) {
            joinSessionId = jobSchedulerJoinOrdersOptions.joinSessionId.getValue();
        } else {
            if (fromSpoolerVariables) {
                joinSessionId = spooler_task.order().params().value("sync_session_id");
            }
        }
        return new JoinOrder(jobChain, orderId, joinSessionId, isMainOrder, this.getCurrentNodeName());
    }

    private boolean setStateText(JoinOrder joinOrder) {
        String stateText = spooler_task.order().params().value("scheduler_join_state_text");
        if (joinOrder.isMainOrder() && !"".equals(stateText)) {
            spooler_task.order().params().set_var("scheduler_join_state_text", "");
            if (RESET_STATE_TEXT.equals(stateText)) {
                stateText = "";
                LOGGER.debug("reset statetext for main order");
                this.setStateText(stateText);
            } else {
                LOGGER.debug("set statetext for main order: " + stateText);
                this.setStateText(stateText);
                suspendOrder();
            }
            return true;
        } else {
            return false;
        }
    }

    private JoinOrder setStateTextAfter(JoinOrder joinOrder) {
        String stateText = String.format("...%s orders from %s required orders received", jobSchedulerJoinOrders.getJoinSerializer()
                .getJoinOrderList().size(joinOrder), jobSchedulerJoinOrdersOptions.required_orders.value());

        JoinOrder mainOrder = null;
        if (joinOrder.isMainOrder()) {
            mainOrder = joinOrder;
            LOGGER.debug("This is the mainOrder. Set state text for main order: " + stateText);
            this.setStateText(stateText);
        } else {
            mainOrder = jobSchedulerJoinOrders.getMainOrder(joinOrder);
            if (mainOrder == null) {
                LOGGER.warn("Could not find main order for joinSessionId: " + joinOrder.getJoinSessionId());
            } else {
                LOGGER.debug("This is a subOrder. Resuming mainOrder to set state text: " + stateText);
                String stateTextCommand = String.format(RESUME_FOR_STATE_TEXT, mainOrder.getJobChain(), mainOrder.getOrderId(), spooler_task.order()
                        .job_chain_node().state(), stateText);
                executeXml(stateTextCommand);
            }
        }
        return mainOrder;
    }

    private String executeXml(String command) {
        LOGGER.debug("... command=" + command);
        String answer = spooler.execute_xml(command);
        LOGGER.debug(answer);
        return answer;
    }

    private void setSerializedObjectWithOrderParam(JoinSerializer joinSerializer, JoinOrder joinOrder) throws Exception {
        if (joinOrder.isMainOrder()) {
            spooler_task.order().params().set_value(JOIN_SERIALIZED_OBJECT_PARAM_NAME, joinSerializer.getSerializedObject());
        } else {
            String check = UUID.randomUUID().toString();
            String checkFromOrder;
            String mainOrderId = joinOrder.getMainOrderId();
            String setParamCommand = String.format(COMMAND_SET_SERIALIZED, joinOrder.getJobChain(), mainOrderId, JOIN_SERIALIZED_OBJECT_PARAM_NAME,
                    joinSerializer.getSerializedObject(), JOIN_SERIALIZED_OBJECT_CHECK_PARAM_NAME, check);
            do {
                executeXml(setParamCommand);
                String getParamCommand = String.format(COMMAND_GET_ORDER, joinOrder.getJobChain(), mainOrderId);
                String answer = executeXml(getParamCommand);
                SOSXMLXPath sosxml = new SOSXMLXPath(new StringBuffer(answer));
                checkFromOrder = sosxml.selectSingleNodeValue(String.format(XML_PATH_PARAM_VALUE, JOIN_SERIALIZED_OBJECT_CHECK_PARAM_NAME));
                if (!check.equals(checkFromOrder)) {
                    LOGGER.debug("...Waiting 3s because mainOrder does not confirm setting parameters.");
                    java.lang.Thread.sleep(3000);
                }
            } while (!check.equals(checkFromOrder));
        }
    }

    private void setSerializedObjectWithSchedulerParam(JoinSerializer joinSerializer, JoinOrder joinOrder) throws IOException {
        spooler.variables().set_var(joinOrder.paramNameForSerializedList(), joinSerializer.getSerializedObject());
    }

    private void setSerializedObject(JoinOrder joinOrder) throws Exception {
        JoinSerializer joinSerializer = jobSchedulerJoinOrders.getJoinSerializer();
        if (joinSerializer != null) {
            if ("true".equalsIgnoreCase(spooler_task.order().params().value("single_mode"))) {
                setSerializedObjectWithSchedulerParam(joinSerializer, joinOrder);
            } else {
                setSerializedObjectWithOrderParam(joinSerializer, joinOrder);
            }

            if (jobSchedulerJoinOrdersOptions.showJoinOrderList.value()) {
                joinSerializer.showJoinOrderList(joinOrder);
            }
        }

    }

    private String getSerializedObjectWithSchedulerParam(JoinOrder joinOrder) {
        return spooler.variables().value(joinOrder.paramNameForSerializedList());
    }

    private String getSerializedObjectWithOrderParam(JoinOrder joinOrder) throws Exception {
        if (joinOrder.isMainOrder()) {
            return spooler_task.order().params().value(JOIN_SERIALIZED_OBJECT_PARAM_NAME);
        } else {
            String mainOrderId = joinOrder.getMainOrderId();
            String getParamCommand = String.format(COMMAND_GET_ORDER, joinOrder.getJobChain(), mainOrderId);
            String answer = executeXml(getParamCommand);
            SOSXMLXPath sosxml = new SOSXMLXPath(new StringBuffer(answer));
            String xmlPath = String.format(XML_PATH_PARAM_VALUE, JOIN_SERIALIZED_OBJECT_PARAM_NAME);
            String paramValue = sosxml.selectSingleNodeValue(xmlPath);
            LOGGER.debug("xml path:" + xmlPath);
            LOGGER.debug("serializedString from order param:" + paramValue);

            return paramValue;

        }
    }

    private String getSerializedObject(JoinOrder joinOrder) throws Exception {
        if (fromSpoolerVariables) {
            return getSerializedObjectWithSchedulerParam(joinOrder);
        } else {
            return getSerializedObjectWithOrderParam(joinOrder);
        }
    }

    private void doProcessing() throws Exception {
        jobSchedulerJoinOrders = new JobSchedulerJoinOrders();
        jobSchedulerJoinOrdersOptions = jobSchedulerJoinOrders.getOptions();
        jobSchedulerJoinOrdersOptions.setCurrentNodeName(this.getCurrentNodeName());
        jobSchedulerJoinOrdersOptions.setAllOptions(getSchedulerParameterAsProperties());
        jobSchedulerJoinOrdersOptions.checkMandatory();
        jobSchedulerJoinOrders.setJSJobUtilites(this);

        JoinOrder joinOrder = getJoinOrder();
        setRequired();

        fromSpoolerVariables = ("true".equalsIgnoreCase(spooler_task.order().params().value("join_store_serialized_object_in_scheduler_parameter")));
        jobSchedulerJoinOrders.setJoinOrder(joinOrder);
        String joinOrderListString = getSerializedObject(joinOrder);

        JoinSerializer joinSerializer = null;
        if ("true".equalsIgnoreCase(spooler_task.order().params().value(JOIN_RESET_LIST))) {
            LOGGER.debug("Reset join order list for: " + joinOrder.getTitle());
            joinSerializer = new JoinSerializer(joinOrderListString);
            joinSerializer.reset(joinOrder);
        } else {
            LOGGER.debug(String.format("Waitung for %s orders", jobSchedulerJoinOrdersOptions.required_orders.value()));

            if (!setStateText(joinOrder)) {

                if (joinOrder.isMainOrder()) {
                    suspendOrder();
                }

                LOGGER.debug("Serialized String:" + joinOrderListString + "-");
                jobSchedulerJoinOrders.setJoinOrderListString(joinOrderListString);

                jobSchedulerJoinOrders.execute();

                JoinOrder mainOrder = setStateTextAfter(joinOrder);

                if (jobSchedulerJoinOrders.isResumeAllOrders() && mainOrder != null) {

                    LOGGER.debug(String.format("reset list for: %s", joinOrder.getTitle()));
                    jobSchedulerJoinOrders.getJoinSerializer().reset(joinOrder);

                    LOGGER.debug("Resuming mainOrder to next state");
                    if (joinOrder.isMainOrder()) {
                        this.setStateText("");
                        spooler_task.order().set_state(spooler_task.order().job_chain_node().next_state());
                        spooler_task.order().set_suspended(false);
                    } else {
                        String resumeCommand = String.format(RESUME_FOR_STATE_TEXT, mainOrder.getJobChain(), mainOrder.getOrderId(), spooler_task
                                .order().job_chain_node().state(), RESET_STATE_TEXT);

                        executeXml(resumeCommand);
                        LOGGER.debug(String.format("Resuming order %s", joinOrder.getOrderId()));

                    }
                }
            }
        }
        setSerializedObject(joinOrder);
    }

}
