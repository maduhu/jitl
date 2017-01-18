package com.sos.jitl.inventory.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.nio.file.Paths;
import java.util.Base64;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sos.hibernate.classes.SOSHibernateConnection;
import com.sos.jitl.reporting.db.DBLayer;

public class InventoryTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryTest.class);
    private String hibernateCfgFile = "C:/sp/jobschedulers/DB-test/jobscheduler_1.11.0-SNAPSHOT3/sp_41110x3/config/hibernate.cfg.xml"; 
    
    @Test
    public void testExecute() {
        try {
            SOSHibernateConnection connection = new SOSHibernateConnection(hibernateCfgFile);
            connection.setAutoCommit(true);
            connection.setIgnoreAutoCommitTransactions(true);
            connection.addClassMapping(DBLayer.getInventoryClassMapping());
            connection.connect();
            InventoryEventUpdateUtil eventUpdates = new InventoryEventUpdateUtil("SP", 40117, connection);
            eventUpdates.execute();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }
    
    
    @Test
    public void testTokenizer(){
        try {
            String schedulerId = "sp_41110x3";
            boolean user = false;
            boolean configuration = false;
            String userVal = null;
            String phrase = null;
            File privateConf = Paths.get("C:/sp/jobschedulers/DB-test/jobscheduler_1.11.0-SNAPSHOT3/sp_41110x3/config/private/private.conf").toFile();
            if(privateConf != null) {
//                StringBuilder strb = new StringBuilder();
                FileInputStream fis = new FileInputStream(privateConf);
                Reader reader = new BufferedReader(new InputStreamReader(fis));
                StreamTokenizer tokenizer = new StreamTokenizer(reader);
                tokenizer.resetSyntax();
                tokenizer.slashStarComments(true);
                tokenizer.slashSlashComments(true);
                tokenizer.eolIsSignificant(false);
                tokenizer.whitespaceChars(0,8);
                tokenizer.whitespaceChars(10,31);
                tokenizer.wordChars(9, 9);
                tokenizer.wordChars(32, 255);
                tokenizer.commentChar('#');
                tokenizer.quoteChar('"');
                tokenizer.quoteChar('\'');
                int ttype       = 0;
//                int lastline    = -1;
//                String s        = "";
                while (ttype != StreamTokenizer.TT_EOF) {
                    ttype = tokenizer.nextToken();
                    String sval = "";
                    switch(ttype) {
                        case StreamTokenizer.TT_WORD : 
                            sval    = tokenizer.sval;
                            if(sval.contains(schedulerId)) {
                                user = true;
                                userVal= sval;
                            } else {
                                user = false;
                            }
                            if(sval.contains("{")) {
                                if ( sval.contains("jobscheduler.master.auth.users")) {
                                    configuration = true;
                                } else {
                                    configuration = false;
                                }
                            }
                            break;
                        case '"':
                            sval    = "\"" + tokenizer.sval + "\"";
                            if(user && configuration) {
                                phrase = sval;
                            }
                            break;
                    }
                }
                phrase = phrase.trim();
                phrase = phrase.substring(1, phrase.length() -1);
                String[] phraseSplit = phrase.split(":");
                if(userVal.replace("=", "").trim().equalsIgnoreCase(schedulerId) && "plain".equalsIgnoreCase(phraseSplit[0])) {
                    byte[] encoded = Base64.getEncoder().encode((schedulerId + ":" + phraseSplit[1]).getBytes());
                    StringBuilder encodedAsString = new StringBuilder();
                    for (byte me : encoded){
                        encodedAsString.append((char)me);
                    }
                    LOGGER.info("userName: " + userVal.replace("=", "").trim() +" | phrase: " + phraseSplit[1] + " : encoded: " + encodedAsString.toString());
                    byte[] decoded = Base64.getDecoder().decode(encoded);
                    StringBuilder decodedAsString = new StringBuilder();
                    for (byte me : decoded){
                        decodedAsString.append((char)me);
                    }
                    LOGGER.info("decoded base64 String: " + decodedAsString.toString());
                }
            }
        } catch (FileNotFoundException e) {
            LOGGER.error("File not found!");
        } catch (IOException e) {
            LOGGER.error("Cannot read from File !");
        }
    }
}
