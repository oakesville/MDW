spool upgrade_55_to_60.lst;

ALTER TABLE DOCUMENT DROP COLUMN PROCESS_INST_ID;
ALTER TABLE DOCUMENT ADD (STATUS_CODE NUMBER(4), STATUS_MESSAGE VARCHAR2(1000), PATH VARCHAR2(1000));

ALTER TABLE TASK_INSTANCE modify (TASK_INSTANCE_REFERRED_AS VARCHAR2(500 BYTE),CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, MOD_DT TIMESTAMP, TASK_START_DT TIMESTAMP, TASK_END_DT TIMESTAMP);
ALTER TABLE WORK_TRANSITION_INSTANCE modify (CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, MOD_DT TIMESTAMP, START_DT TIMESTAMP, END_DT TIMESTAMP);
ALTER TABLE VARIABLE_INSTANCE modify (CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, MOD_DT TIMESTAMP);
ALTER TABLE ACTIVITY_INSTANCE modify (CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, MOD_DT TIMESTAMP, START_DT TIMESTAMP, END_DT TIMESTAMP);
ALTER TABLE PROCESS_INSTANCE modify (CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, MOD_DT TIMESTAMP, START_DT TIMESTAMP, END_DT TIMESTAMP);
ALTER TABLE EVENT_WAIT_INSTANCE modify (CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, MOD_DT TIMESTAMP);
ALTER TABLE EVENT_INSTANCE modify (CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, CONSUME_DT TIMESTAMP);
ALTER TABLE EVENT_LOG modify (CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, MOD_DT TIMESTAMP);
ALTER TABLE DOCUMENT modify (CREATE_DT TIMESTAMP DEFAULT SYSTIMESTAMP, MODIFY_DT TIMESTAMP);

UPDATE DOCUMENT set OWNER_TYPE = 'ADAPTER_REQUEST' where OWNER_TYPE = 'ADAPTOR_REQUEST';
UPDATE DOCUMENT set OWNER_TYPE = 'ADAPTER_RESPONSE' where OWNER_TYPE = 'ADAPTOR_RESPONSE';

-- not used when mongodb is present - except for pre-existing coming from 5.5
CREATE TABLE DOCUMENT_CONTENT
(
  DOCUMENT_ID         NUMBER(20),
  CONTENT             CLOB      NOT NULL
);
ALTER TABLE DOCUMENT_CONTENT ADD (
  CONSTRAINT DOCCONTENT_DOCUMENT_FK
  FOREIGN KEY (DOCUMENT_ID)
  REFERENCES DOCUMENT (DOCUMENT_ID));
CREATE INDEX DOCCONTENT_DOCUMENT_FK ON DOCUMENT_CONTENT
(DOCUMENT_ID);

-- move (or copy from DOCUMENT.CONTENT to DOCUMENT_CONTENT.CONTENT)
INSERT INTO DOCUMENT_CONTENT (DOCUMENT_ID, CONTENT) SELECT DOCUMENT_ID, CONTENT FROM DOCUMENT;

-- If the above statement fails due to very large number of rows, perform the below statement multiple times until all rows have been copied

-- INSERT INTO DOCUMENT_CONTENT (DOCUMENT_ID, CONTENT) SELECT DOCUMENT_ID, CONTENT FROM DOCUMENT AS d
-- JOIN (SELECT COALESCE(MAX(DOCUMENT_ID), 0) AS offset FROM DOCUMENT_CONTENT) AS dc
-- ON  d.DOCUMENT_ID > dc.offset
-- ORDER BY d.DOCUMENT_ID LIMIT 100000;

-- Alternatively, do the below to create stored procedure to repeat statement above automatically.
-- This works as is when using Quantum plugin in Eclipse.
-- If using native mySQL client shell, remove the '\' before each ';' and add a statement to change delimeter before and after the code block
-- "delimiter ;;" (before) and "delimiter ;" (after) and then add the extra ; (so you have ;; at end of each line)

-- DROP PROCEDURE IF EXISTS copyDocumentContent;
-- CREATE PROCEDURE copyDocumentContent(p1 INT)
-- BEGIN
-- DECLARE d_count INT\;
-- DECLARE dc_count INT DEFAULT 0\;
-- SELECT COUNT(DOCUMENT_ID) INTO d_count FROM DOCUMENT\;
-- REPEAT
-- INSERT INTO DOCUMENT_CONTENT (DOCUMENT_ID, CONTENT) SELECT DOCUMENT_ID, CONTENT FROM DOCUMENT AS d
-- JOIN (SELECT COALESCE(MAX(DOCUMENT_ID), 0) AS offset FROM DOCUMENT_CONTENT) AS dc
-- ON d.DOCUMENT_ID > dc.offset
-- ORDER BY d.DOCUMENT_ID LIMIT p1\;
-- SELECT COUNT(DOCUMENT_ID) INTO dc_count FROM DOCUMENT_CONTENT\;
-- UNTIL dc_count >= d_count END REPEAT\;
-- END;
-- CALL copyDocumentContent(100000);

-- iff the copy was successful, drop the CONTENT column from DOCUMENT table as a manual step.
-- Make sure you validate row counts from both tables BEFORE dropping the CONTENT column from DOCUMENT

-- ALTER TABLE DOCUMENT DROP COLUMN CONTENT;


CREATE TABLE INSTANCE_INDEX (
  INSTANCE_ID      	NUMBER(38)  NOT NULL,
  OWNER_TYPE		VARCHAR2(30 BYTE) NOT NULL,
  INDEX_KEY         VARCHAR2(64) NOT NULL,
  INDEX_VALUE       VARCHAR2(256) NOT NULL,
  CREATE_DT     DATE DEFAULT SYSDATE NOT NULL
);

ALTER TABLE INSTANCE_INDEX ADD (
  CONSTRAINT INSTANCE_INDEX_PK
  PRIMARY KEY (INSTANCE_ID,OWNER_TYPE,INDEX_KEY)
  USING INDEX);

CREATE INDEX INSTANCEIDX_IDXKEY_FK
ON INSTANCE_INDEX (INDEX_KEY);

-- move (or copy from TASK_INST_INDEX to INSTANCE_INDEX)
INSERT INTO INSTANCE_INDEX (INSTANCE_ID, OWNER_TYPE, INDEX_KEY, INDEX_VALUE, CREATE_DT) SELECT TASK_INSTANCE_ID, 'TASK_INSTANCE', INDEX_KEY, INDEX_VALUE, CREATE_DT FROM TASK_INST_INDEX;

-- If the above statement fails due to very large number of rows, perform the below statement multiple times until all rows have been copied

-- INSERT INTO INSTANCE_INDEX (INSTANCE_ID, OWNER_TYPE, INDEX_KEY, INDEX_VALUE, CREATE_DT) SELECT TASK_INSTANCE_ID, 'TASK_INSTANCE', INDEX_KEY, INDEX_VALUE, CREATE_DT FROM TASK_INST_INDEX AS d
-- JOIN (SELECT COALESCE(MAX(INSTANCE_ID), 0) AS offset FROM INSTANCE_INDEX) AS dc
-- ON  d.TASK_INSTANCE_ID > dc.offset
-- ORDER BY d.TASK_INSTANCE_ID LIMIT 100000;

-- Alternatively, do the below to create stored procedure to repeat statement above automatically.
-- This works as is when using Quantum plugin in Eclipse.
-- If using native mySQL client shell, remove the '\' before each ';' and add a statement to change delimeter before and after the code block
-- "delimiter ;;" (before) and "delimiter ;" (after) and then add the extra ; (so you have ;; at end of each line)

-- DROP PROCEDURE IF EXISTS copyTaskIndexes;
-- CREATE PROCEDURE copyTaskIndexes(p1 INT)
-- BEGIN
-- DECLARE d_count INT\;
-- DECLARE dc_count INT DEFAULT 0\;
-- SELECT COUNT(TASK_INSTANCE_ID) INTO d_count FROM TASK_INST_INDEX\;
-- REPEAT
-- INSERT INTO INSTANCE_INDEX (INSTANCE_ID, OWNER_TYPE, INDEX_KEY, INDEX_VALUE, CREATE_DT) SELECT TASK_INSTANCE_ID, 'TASK_INSTANCE', INDEX_KEY, INDEX_VALUE, CREATE_DT FROM TASK_INST_INDEX AS d
-- JOIN (SELECT COALESCE(MAX(INSTANCE_ID), 0) AS offset FROM INSTANCE_INDEX) AS dc
-- ON d.TASK_INSTANCE_ID > dc.offset
-- ORDER BY d.TASK_INSTANCE_ID LIMIT p1\;
-- SELECT COUNT(INSTANCE_ID) INTO dc_count FROM INSTANCE_INDEX\;
-- UNTIL dc_count >= d_count END REPEAT\;
-- END;
-- CALL copyTaskIndexes(100000);

-- iff the copy was successful, drop the TASK_INST_INDEX table as a manual step.
-- Make sure you validate row counts from both tables BEFORE dropping the TASK_INST_INDEX table

-- DROP TABLE TASK_INST_INDEX;

-- solutions
CREATE TABLE SOLUTION
(
  SOLUTION_ID    NUMBER(20)        NOT NULL,
  ID             VARCHAR2(128)     NOT NULL, -- TODO: unique constraint
  NAME           VARCHAR2(1024)    NOT NULL,
  OWNER_TYPE     VARCHAR2(128)     NOT NULL,
  OWNER_ID       VARCHAR2(128)     NOT NULL,
  CREATE_DT      DATE              DEFAULT SYSDATE,
  CREATE_USR     VARCHAR2(30)      DEFAULT USER,
  MOD_DT         DATE,
  MOD_USR        VARCHAR2(30),
  COMMENTS       VARCHAR2(1024)
);

CREATE TABLE SOLUTION_MAP
(
  SOLUTION_ID    NUMBER(20)        NOT NULL,
  MEMBER_TYPE    VARCHAR2(128)     NOT NULL,
  MEMBER_ID      VARCHAR2(128)     NOT NULL,
  CREATE_DT      DATE              NOT NULL,
  CREATE_USR     VARCHAR(30)       NOT NULL,
  MOD_DT         DATE,
  MOD_USR        VARCHAR2(30),
  COMMENTS       VARCHAR2(1024)
);

CREATE TABLE VALUE
(
  NAME            VARCHAR(1024)    NOT NULL,
  VALUE           VARCHAR(2048)    NOT NULL,
  OWNER_TYPE      VARCHAR(128)     NOT NULL,
  OWNER_ID        VARCHAR(128)     NOT NULL,
  CREATE_DT       DATE        NOT NULL,
  CREATE_USR      VARCHAR(30)      NOT NULL,
  MOD_DT          DATE,
  MOD_USR         VARCHAR(30),
  COMMENTS        VARCHAR(1024)
);

ALTER TABLE SOLUTION ADD (
  CONSTRAINT SOLUTION_ID_PK
  PRIMARY KEY (SOLUTION_ID)
  USING INDEX
);

ALTER TABLE SOLUTION_MAP ADD
(
   PRIMARY KEY (SOLUTION_ID,MEMBER_TYPE,MEMBER_ID)
);

ALTER TABLE value ADD
(
   PRIMARY KEY (Name,Owner_type,owner_id)
);

ALTER TABLE SOLUTION ADD  (
   UNIQUE(ID)
);

CREATE INDEX SOLUTION_MAP_IDX
ON SOLUTION_MAP (SOLUTION_ID);

ALTER TABLE SOLUTION_MAP ADD (
  CONSTRAINT SOLUTION_MAP_FK
  FOREIGN KEY (SOLUTION_ID)
  REFERENCES SOLUTION(SOLUTION_ID)
);

ALTER TABLE TASK_INSTANCE ADD (TASK_TITLE VARCHAR2(512));

ALTER TABLE ATTACHMENT MODIFY (CREATE_USR VARCHAR2(100 BYTE) NOT NULL);
ALTER TABLE ATTACHMENT MODIFY (MOD_USR VARCHAR2(100 BYTE) NOT NULL);
ALTER TABLE ATTACHMENT MODIFY (ATTACHMENT_CONTENT_TYPE VARCHAR2(1000));
ALTER TABLE ATTACHMENT DROP COLUMN ATTACHMENT_STATUS;
ALTER TABLE ATTACHMENT DROP COLUMN COMMENTS;

ALTER TABLE INSTANCE_NOTE MODIFY (CREATE_USR VARCHAR2(100 BYTE) NOT NULL);
ALTER TABLE INSTANCE_NOTE MODIFY (MOD_USR VARCHAR2(100 BYTE) NOT NULL);
ALTER TABLE INSTANCE_NOTE MODIFY  INSTANCE_NOTE_NAME VARCHAR2(256 BYTE);
ALTER TABLE INSTANCE_NOTE DROP COLUMN COMMENTS;

ALTER TABLE  PROCESS_INSTANCE add TEMPLATE VARCHAR2(256 BYTE);

CREATE TABLE INSTANCE_TIMING
(
  INSTANCE_ID     NUMBER(16)    NOT NULL,
  OWNER_TYPE      VARCHAR(30)   NOT NULL,
  ELAPSED_MS      NUMBER(24)    NOT NULL
);

ALTER TABLE INSTANCE_TIMING ADD
(
  CONSTRAINT timing_primary_key
  PRIMARY KEY(INSTANCE_ID,OWNER_TYPE)
);

spool off;
