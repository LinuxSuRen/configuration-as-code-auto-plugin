# Jenkins Configuration as Code Auto Plugin

This plugin provides a solution to allow users to upgrade their Jenkins Configuration-as-Code config file.

We deal with three config files:

|Config file path|Description|
|---|---|
|`${JENKINS_HOME}/war/jenkins.yaml`|Initial config file, put the new config files in here|
|`${JENKINS_HOME}/war/WEB-INF/jenkins.yaml`|Should be the last version of config file|
|`${JENKINS_HOME}/war/WEB-INF/jenkins.yaml.d/user.yaml`|All current config file, auto generate it when a user change the config|
