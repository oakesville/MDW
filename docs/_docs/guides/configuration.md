---
permalink: /docs/guides/configuration/
title: MDW Configuration
---

## project.yaml
  - Used at design time by [MDW Studio](../mdw-studio/) and the [CLI](../../getting-started/cli/).
  - Belongs in the root directory of your project (or root module for multi-module builds).
  - Contains bootstrap information to locate [mdw.yaml](#mdwyaml) and project assets.
  - Also may include custom values (like workgroups) that are needed at design time.
  - Not used at runtime.   
    ```yaml
    # paths are relative to the location of this file
    mdw:
      version: 6.1.28

    asset:
      location: assets

    config:
      location: config

    data:
      workgroups:
        - Developers
        - MDW Support
    ```

## mdw.yaml
  - Used at runtime by the MDW server.
  - Specify your config directory via the `-Dmdw.config.location` system property.
  - To convert from old-style mdw.properties to mdw.yaml, run the [CLI](../../getting-started/cli/) command `mdw convert`.
  - Encrypted values (via `mdw encrypt`) start with `~[` and end with `]`.
    Requires MDW_APP_TOKEN environment variable.  Note: when running the CLI command `mdw encrypt --input=my_value_to_encrypt`,
    the MDW_APP_TOKEN value must be defined in the shell where the command is executed.
  - Here's an annotated example describing MDW's configuration options:   
    (**Please** do not use this as a starting point as it contains inappropriate/non-default values for illustration.  
      Instead run the `mdw init` CLI command to generate from the latest template).   

    ```yaml
    app:
      id: my-project  # required
      configs:
      - myconfig.yaml

    # container settings (leave these properties as shown except for unusual scenarios)    
    container:
      datasource.provider: Tomcat  # required
      messenger: jms  # required
      jms.provider: ActiveMQ  # required
      threadpool.provider: MDW  # required
      classic.classloading: false  # Load classes from runnable JAR/WAR first - default=false 

    # optional settings for activemq (For overriding values in application-context.xml)
    activemq:
      location: activemq-data   # Has to be relative to startup directory - default=../activemq-data
      port: 61619   # default=61618
      maxConnections: 10   # Max connections in JMS pool - default=8

    # database connection information (below is typical for embedded db)
    database:
      driver: org.mariadb.jdbc.Driver  # required
      url: jdbc:mariadb://localhost:3308/mdw  # required
      username: mdw  # required
      password: mdw  # or encrypted form: ~[HDIGDNBIDJCCNHOB]
      poolsize: 10  # default=5
      poolMaxIdle: 3  # default=5
      validationQuery: select 1 from dual  # required
      transaction.isolation: 4 # default=2 (READ_COMMITTED)
      # save datetime/timestamp using microsecond precision
      microsecond.precision: true  # default=false
      # log all queries and timings
      trace: false  # default=false

    # embedded db
    db.base.location: ../data  # default=assetLoc + "/../data/db"
    db.data.location: ../data/mdw  # default=assetLoc + "/../data/mdw"
    db.startup:  # extra startup params (default = none)
      - --lower-case-table-names=1

    # optional mongodb (requires asset package com.centurylink.mdw.mongo)
    mongodb:
      host: localhost  # Can also be URI (i.e. host1:27017,host2:27017,host3:27017)
      port: 27017  # default = 27017 (Note: If host is URI, port property is ignored)
      name: mdw  # default = mdw

    # asset location info
    asset:
      location: assets  # required
      history.days: 730 # default=(0 in dev; 365 elsewhere)

    # git repository
    git:
      local.path: .  # required
      remote.url: https://github.com/CenturyLinkCloud/mdw-demo.git  # required
      branch: master  # required (unless using tag below - tag is ignored if branch is specified)
      tag: march_2020_release  # optional (used instead of branch above)
      auto.pull: false  # default=false
      user: anonymous
      password: onlyifrequired
      trusted.host: mytrustedhost.com  # blindly trust https certificate
      fetch: true  # fetch when checking for diffs - default=true except in dev   

    temp.dir: mdw/.temp  # default=mdw/.temp
    attachments.dir: mdw/attach  # default=mdw/attachments

    hub.url: http://localhost:8080/mdw  # required
    services.url: http://localhost:8080/mdw  # required
    websocket.url: none # 'none' to use polling; otherwise don't set
    discovery.url: https://repo.maven.apache.org/maven2  # default=https://repo.maven.apache.org/maven2
    docs.url: https://centurylinkcloud.github.io/mdw/docs  # default=https://centurylinkcloud.github.io/mdw/docs

    # for SLF4J and SimpleLogger
    logging:
      level: DEBUG  # default=INFO
      activity:
        enabled: true  # enable activity logging default=true
        cleanup:
          enabled: true  # default=true
          retain: 30  # default=90 (days)
      roller:
        enabled: true
        retain: 7 # default=30 (days)
        files:
          - /var/log/mdw/mdw.log
    
    # scripting support (custom executors should implement ScriptExecutor)
    script:
      executors:
        groovy: com.centurylink.mdw.script.GroovyExecutor
        javascript: com.centurylink.mdw.script.JavaScriptExecutor

    # JavaMail notifications
    mail:
      smtp.host: mymailhost
      smtp.port: 25
      connection.timeout: 10000
      smtp.timeout: 10000
      user: smtpuser
      password: smtppass

    # Slack notifications
    slack.webhook.url: ~[encrypted_webhook_url]

    # MS Teams notifications
    msteams.webhook.url: ~[encrypted_webhook_url]

    # for LDAP adapter activity
    ldap:
      host: ldap.myorg.com
      port: 1636
      base.dn: dc=mydc,dc=example

    # scheduled jobs registration    
    timer.InitialDelay: 120  # (seconds) default=120
    timer.CheckInterval: 60  # (seconds) default=60
    timer.ThresholdForDelay: 30  # (minutes) default=60
    
    # scheduled job for retriggering stuck activites 
    StuckActivities:
      job:
        enabled: true   # default=false
        scheduler: 30 12 * * ? * # cron expression defining the schedule (example shows daily at 12:30 AM)
        ActivityAgeInSeconds: 2000 # minimum age of eligible activities default=1800
        MaximumActivities: 100 # maximum number of activities processed per cycle default=10
        
    # scheduled job for periodic cleanup of old completed mdw data from the DB
    cleanup:
      job:
        enabled: true   # this enables the scheduling of the cleanup job
        cleanupscheduler: 30 00 * * ? * # the schedule to run . this runs at 12:30 AM
        ProcessExpirationAgeInDays: 180
        maxProcesses: 0
        eventExpirationDays: 180
        commitInterval: 1000
        RuntimeCleanupScript: Cleanup-Runtime.sql
    
    # Wait Activity Fallback Processing
    wait:
      fallback:
        stagger: 60  # seconds offset between scheduling scan for each activity - default=30
        max: 20  # maximum number of activity instances to trigger per cycle - default=100
        age: 1200 # minimum age in seconds of waiting activity instances to process - default=600
    timer.wait.fallback.enabled: true  # enable fallback processing for timer wait activities - default=false
    dependencies.wait.fallback.enabled: true  # enable fallback processing for dependencies waits - default=false

    # Custom JWT Providers   
    jwt:
      custom:   # Example of a custom JWT provider
        issuer: example.com # issuer of token
        userClaim: userid # claim containing the authenticated user
        key: # actual public rsa encryption key to decrypt JWT
        algorithm: # Optional constraint to verify against JWT
        subject:  # Optional constraint to verify against JWT
      provider2:  # Example of second custom JWT provider
        issuer: example.test.com # issuer of token
        userClaim: userid # claim containing the authenticated user
        key: # actual public rsa encryption key to decrypt JWT
        algorithm: # Optional constraint to verify against JWT
        subject:  # Optional constraint to verify against JWT

    jwt.preserve: true   # Passes JWT to service in headers Map object - default=false

    # https://github.com/CenturyLinkCloud/mdw/blob/master/mdw-workflow/assets/com/centurylink/mdw/system/filepanel/readme.md
    filepanel:
      root.dirs: ./logs,./config
      exclude.patterns: '**/temp/*'
      masked.lines: 'password:'  # default=mdw.database.password=,LDAP-AppPassword=,password:

    # Dashboard system metrics history
    system.metrics.location: ./logs  # if not specified, no history will be retained

    # https://centurylinkcloud.github.io/mdw/docs/guides/tuning/
    threadpool:
      max_threads: 10  # default=10
      core_threads: 5  # default=((max_threads/2)>50?50:(max_threads/2))
      queue_size: 20  # default=(max_threads>100?100:20)
      keep_alive: 300  # default=300
      termination_timeout: 120  # default=120
      # prefix, add worker_name.min_threads/max_threads
      worker: my-worker

    jms:
      listener:
        poll.interval: 10  # (ms) default=5
        receive.timeout: 180  # (ms) default=300

    java:
      compiler.options: '-deprecation -nowarn'
      compiler.classpath: '/opt/important/impl.jar'
      library.path: '/opt/custom:/opt/other'
      runtime.classpath: '/opt/important/impl.jar'

    milestone:
      groups:  # list of milestone groups
        GroupOne:
          color: '#990099'
        GroupTwo:
          color: '#ff9900'
          description: 'Describe me'

    activity:
      resume.delay: 3  # (seconds) default=2
      active.max.retry: 10  # default=5

    process:
      launch.delay: 3  # (seconds) default=2
      uniqueMasterRequestId: true  # default=false

    # https://centurylinkcloud.github.io/mdw/docs/guides/tuning/#performance-levels
    performance:
      level.service: 5  # default=3
      level.regular: 5  # default=3

    # capture activity timings
    timings:
      activities: true # default=false

    # formatting options for json output
    json:
      pretty.indent: 2 # pretty-print output with this indent level
      ordered.keys: true # property and object names are sorted (default=true)
      false.values.output: false # include false booleans when serializing output (default=false)

    engine:
      use.transaction: false  # default=false

    transaction:
      retry.interval: 500  # (ms) default=1000
      retry.max: 5  # default=3

    internal.event:
      consume.retry.sleep: 3  # (seconds) default=2
      dev.cleanup: true  # default=true

    scheduled.events:
      max.batch.size: 500  # default=1000
      memory.range: 2880  # (minutes) default=1440

    unscheduled.events
      check.delay: 120  # (seconds) default=90
      check.interval: 180  # (seconds) default=300
      max.batch.size: 500  # default=1000
      # (WARNING: Never set lower than mdw.timer.ThresholdForDelay which is in minutes)
      min.age: 3600  # default=3600, min=300

    hub:
      override.package: my-hub  # default=mdw-hub
      action.definition: my-task-actions.xml  # default=mdw-task-actions.xml

    translator:
      xmlbeans.load.options:  # for XmlOptions.put()
      xmlbeans.save.options:  # for XmlOptions.put()

    test:
      results.location: ./mytestresults  # default=git/local.path+"/testResults"
      summary.file: myfile.json  # default=mdw-function-test-results.json

    routing.servers:
      localhost:
        ports:
        - 8080

    requestrouting:
      enabled: true  # default=false
      https.enabled: true  # default=false
      default.strategy: com.centurylink.mdw.routing.LoadBasedRoutingStrategy
      active.server.interval: 20  # (seconds) default=15
      timeout: 180  # (seconds) default=300

    ```

## access.yaml
  - Controls access to MDWHub and the services API
  - Here's an annotated example:

    ```yaml
    # If upstreamHosts is populated then access is restricted to this list
    # unless running in dev mode (-Dmdw.runtime.env=dev).
    upstreamHosts:
      - 127.0.0.1
      - 0:0:0:0:0:0:0:1

    # Auth methods supported include ct (ClearTrust Web Agent)
    # or mdw (or other JWT provider).  Default is mdw.
    # If using other JWT provider, must configure via properties (mdw.yml)
    authMethod: mdw

    # This is the header we trust to specify the authenticated user id.
    # NOTE: This is only secure when upstreamHosts is enforced.
    #authUserHeader: remote-user

    # Allows access to all UI functions and Service APIs permitted for this
    # user without authenticating.  Requires "-Dmdw.runtime.env=dev" system property.
    devUser: mdwapp

    # Auth exclusions are patterns that can be accessed directly
    # without authentication even when running with protection
    # (upstreamHosts != null & not in dev mode).
    # In the cases where the pattern accesses a service, it falls to
    # the specific service to handle access (i.e. based on HTTP method and/or path)
    authExclusions:
      - '/login'
      - '/error'
      - '/js/nav.json'  
      - '/images/*'
      - '/css/*'
      - '/doc/*'
      - '/javadoc/*'
      - '/api-docs/*'
      - '/services/AppSummary'
      - '/services/System/sysInfo'
      - '/services/com/centurylink/mdw/slack'
      - '/services/com/centurylink/mdw/slack/event'

    # Headers appended to all HTTP servlet responses.
    responseHeaders:
      X-UA-Compatible: IE=Edge

    # Session timeout in seconds (if not specified, container will govern).
    sessionTimeout: 3600

    # Allowing any authenticated user means even those not in mdw's db.
    allowAnyAuthenticatedUser: false

    # Special options for logging
    loggingOptions:
      logResponseTimes: false
      logHeaders: false
      logParameters: false
    ```

## seed_users.json
  - With [Embedded DB](https://github.com/CenturyLinkCloud/mdw/blob/master/mdw-workflow/assets/com/centurylink/mdw/db/readme.md),
    designated users are inserted when db is created (no effect once db exists -- delete data directory to recreate with different users).
  - Here's an example:
    ```json
    {
      "users":  [
        {
          "name": "MDW Application",
          "id": "mdwapp",
          "attributes": {
            "Email": "mdwapp@centurylink.com"
          },      
          "groups": [
            "Developers",
            "Site Admin"
          ],
          "roles": [
            "User Admin",
            "Process Execution",
            "Process Design",
            "Task Execution"
          ]            
        }
      ]
    }    
    ```
## *.spring assets
  - Include .spring assets in any package to make resources like datasources available to your application.
  - Here's an example .spring asset that declares a datasource:
    ```xml
    <beans xmlns="http://www.springframework.org/schema/beans"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd    ">
      <bean id="myDataSource" class="org.apache.commons.dbcp2.BasicDataSource" destroy-method="close">
        <property name="driverClassName" value="#{T(com.centurylink.mdw.config.PropertyManager).getProperty('my.database.driver')}" />
        <property name="url" value="#{T(com.centurylink.mdw.config.PropertyManager).getProperty('my.db.url')}" />
        <property name="username" value="#{T(com.centurylink.mdw.config.PropertyManager).getProperty('my.db.username')}" />
        <property name="password" value="#{T(com.centurylink.mdw.config.PropertyManager).getProperty('my.db.password')}" />
        <property name="maxTotal" value="#{T(com.centurylink.mdw.config.PropertyManager).getProperty('my.database.poolsize')}" />
        <property name="maxIdle" value="#{T(com.centurylink.mdw.config.PropertyManager).getProperty('my.database.poolMaxIdle')}" />
        <property name="validationQuery" value="SELECT 1 FROM DUAL" />
        <property name="testOnBorrow" value="true" />
        <property name="testWhileIdle" value="true" />
        <property name="removeAbandonedOnBorrow" value="true" />
        <property name="logAbandoned" value="true" />
        <property name="removeAbandonedTimeout" value="#{T(com.centurylink.mdw.config.PropertyManager).getIntegerProperty('mdw.database.timeout',1000)}" />
      </bean>
    </bean>
    ```