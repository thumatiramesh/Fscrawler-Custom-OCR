#!/bin/bash
if [ "$TRAVIS_SECURE_ENV_VARS" = true ]; then
   mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent install sonar:sonar -DskipIntegTests=true
fi
if [ "$TRAVIS_PULL_REQUEST" = false ] && [ "$TRAVIS_TAG" = '' ]; then
  mvn deploy -DskipTests --settings deploy-settings.xml
fi

