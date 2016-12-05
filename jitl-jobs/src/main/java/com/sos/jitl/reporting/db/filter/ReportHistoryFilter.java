package com.sos.jitl.reporting.db.filter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import org.apache.log4j.Logger;
import com.sos.hibernate.classes.SOSHibernateIntervalFilter;

public class ReportHistoryFilter extends SOSHibernateIntervalFilter {

    private static final Logger LOGGER = Logger.getLogger(ReportHistoryFilter.class);
    private String dateFormat = "yyyy-MM-dd HH:mm:ss.SSS";
    private Date executedFrom;
    private Date executedTo;
    private Date startTime;
    private Date endTime;
    private String schedulerId = "";
    private ArrayList<String> listOfFolders;

    public ReportHistoryFilter() {
        super();
    }

    public ArrayList<String> getListOfFolders() {
        return listOfFolders;
    }

    public void addFolderPath(String folder) {
        if (listOfFolders == null) {
            listOfFolders = new ArrayList<String>();
        }
        listOfFolders.add(folder);
    }

    @Override
    public String getDateFormat() {
        return dateFormat;
    }

    @Override
    public void setDateFormat(final String dateFormat) {
        this.dateFormat = dateFormat;
    }

    public void setExecutedFrom(final String executedFrom, String parseDateFormat) throws ParseException {
        if ("".equals(executedFrom)) {
            this.executedFrom = null;
        } else {
            SimpleDateFormat formatter = new SimpleDateFormat(parseDateFormat);
            setExecutedFrom(formatter.parse(executedFrom));
        }
    }

    public Date getExecutedFrom() {
        return executedFrom;
    }

    public Date getExecutedTo() {
        return executedTo;
    }

    public void setExecutedTo(final String executedTo, String parseDateFormat) throws ParseException {
        if ("".equals(executedTo)) {
            this.executedTo = null;
        } else {
            SimpleDateFormat formatter = new SimpleDateFormat(parseDateFormat);
            setExecutedTo(formatter.parse(executedTo));
        }
    }

    public String getSchedulerId() {
        return schedulerId;
    }

    public void setSchedulerId(final String schedulerId) {
        this.schedulerId = schedulerId;
    }

    public void setExecutedFrom(final Date from) {
        SimpleDateFormat formatter = new SimpleDateFormat(dateFormat);
        String d = formatter.format(from);
        try {
            executedFrom = formatter.parse(d);
        } catch (ParseException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public void setExecutedTo(final Date to) {
        SimpleDateFormat formatter = new SimpleDateFormat(dateFormat);
        String d = formatter.format(to);
        try {
            executedTo = formatter.parse(d);
        } catch (ParseException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public void setStartTime(final Date start) {
        startTime = start;
    }

    public void setEndTime(final Date end) {
        endTime = end;
    }

    public Date getStartTime() {
        return startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    @Override
    public void setIntervalFromDate(Date d) {
        this.executedFrom = d;
    }

    @Override
    public void setIntervalToDate(Date d) {
        this.executedTo = d;
    }

    @Override
    public void setIntervalFromDateIso(String s) {
    }

    @Override
    public void setIntervalToDateIso(String s) {
    }

}