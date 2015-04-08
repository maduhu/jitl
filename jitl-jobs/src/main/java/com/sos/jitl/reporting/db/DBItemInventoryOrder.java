package com.sos.jitl.reporting.db;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.hibernate.annotations.Type;

import sos.util.SOSString;

import com.sos.hibernate.classes.DbItem;

@Entity
@Table(name = DBLayer.TABLE_INVENTORY_ORDERS)
@SequenceGenerator(
		name=DBLayer.TABLE_INVENTORY_ORDERS_SEQUENCE, 
		sequenceName=DBLayer.TABLE_INVENTORY_ORDERS_SEQUENCE,
		allocationSize=1)
public class DBItemInventoryOrder extends DbItem implements Serializable{
	private static final long serialVersionUID = 1L;
	
	private static final int TITLE_MAX_LENGTH = 255;
	
	/** Primary key */
	private Long id;

	/** Foreign key INVENTORY_INSTANCES.ID */
	private Long instanceId;
	/** Foreign key INVENTORY_FILES.ID */
	private Long fileId;
	/** Foreign key INVENTORY_JOB_CHAINS.NAME */
	private String jobChainName;
	
	/** Others */
	private String name;
	private String baseName;
	private String orderId;
	private String title;
	private boolean isRuntimeDefined;
	private Date created;
	private Date modified;
	
	public DBItemInventoryOrder(){}
	
	/** Primary key */
    @Id
    @GeneratedValue(
        	strategy=GenerationType.AUTO,
        	generator=DBLayer.TABLE_INVENTORY_ORDERS_SEQUENCE)
    @Column(name="`ID`", nullable = false)
    public Long getId() {
        return this.id;
    }
    
    @Id
    @GeneratedValue(
        	strategy=GenerationType.AUTO,
        	generator=DBLayer.TABLE_INVENTORY_ORDERS_SEQUENCE)
    @Column(name="`ID`", nullable = false)
    public void setId(Long val) {
       this.id = val;
    }
    
    /** Foreign key INVENTORY_INSTANCES.ID */
    @Column(name="`INSTANCE_ID`", nullable = false)
    public Long getInstanceId() {
        return this.instanceId;
    }
    
    @Column(name="`INSTANCE_ID`", nullable = false)
    public void setInstanceId(Long val) {
       this.instanceId = val;
    }
    
    /** Foreign key INVENTORY_FILES.ID */
    @Column(name="`FILE_ID`", nullable = false)
    public Long getFileId() {
        return this.fileId;
    }
    
    @Column(name="`FILE_ID`", nullable = false)
    public void setFileId(Long val) {
       this.fileId = val;
    }
    
    /** Foreign key INVENTORY_JOB_CHAINS.NAME */
    @Column(name = "`JOB_CHAIN_NAME`", nullable = false)
   	public void setJobChainName(String val) {
   		this.jobChainName = val;
   	}

   	@Column(name = "`JOB_CHAIN_NAME`", nullable = false)
   	public String getJobChainName() {
   		return this.jobChainName;
   	}
    
    /** Others */
   	@Column(name = "`NAME`", nullable = false)
	public void setName(String val) {
		this.name = val;
	}

	@Column(name = "`NAME`", nullable = false)
	public String getName() {
		return this.name;
	}
	
    @Column(name = "`BASENAME`", nullable = false)
	public void setBaseName(String val) {
		this.baseName = val;
	}

	@Column(name = "`BASENAME`", nullable = false)
	public String getBaseName() {
		return this.baseName;
	}
   	
    @Column(name = "`ORDER_ID`", nullable = false)
	public void setOrderId(String val) {
		this.orderId = val;
	}

	@Column(name = "`ORDER_ID`", nullable = false)
	public String getOrderId() {
		return this.orderId;
	}
	
	@Column(name = "`TITLE`", nullable = true)
	public void setTitle(String val) {
		if(SOSString.isEmpty(val)){
			val = null;
		}
		else{
			if(val.length() > TITLE_MAX_LENGTH){
				val = val.substring(0,TITLE_MAX_LENGTH);
			}
		}
		this.title = val;
	}

	@Column(name = "`TITLE`", nullable = true)
	public String getTitle() {
		return this.title;
	}
	
	@Column(name = "`IS_RUNTIME_DEFINED`", nullable = false)
	@Type(type="numeric_boolean")
	public void setIsRuntimeDefined(boolean val) {
		this.isRuntimeDefined = val;
	}

	@Column(name = "`IS_RUNTIME_DEFINED`", nullable = false)
	@Type(type="numeric_boolean")
	public boolean getIsRuntimeDefined() {
		return this.isRuntimeDefined;
	}
	
	@Temporal (TemporalType.TIMESTAMP)
	@Column(name = "`CREATED`", nullable = false)
	public void setCreated(Date val) {
		this.created = val;
	}

	@Temporal (TemporalType.TIMESTAMP)
	@Column(name = "`CREATED`", nullable = false)
	public Date getCreated() {
		return this.created;
	}

	@Temporal (TemporalType.TIMESTAMP)
	@Column(name = "`MODIFIED`", nullable = false)
	public void setModified(Date val) {
		this.modified = val;
	}

	@Temporal (TemporalType.TIMESTAMP)
	@Column(name = "`MODIFIED`", nullable = false)
	public Date getModified() {
		return this.modified;
	}
}