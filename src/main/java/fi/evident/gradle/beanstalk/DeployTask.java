package fi.evident.gradle.beanstalk;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DeployTask extends DefaultTask {

    private BeanstalkPluginExtension beanstalk;
    private BeanstalkDeployment deployment;
    private Object war;

    private static final Logger log = LoggerFactory.getLogger(DeployTask.class);
    @TaskAction
    protected void deploy() {
        String versionLabel = getProject().getVersion().toString();
        if (versionLabel.endsWith("-SNAPSHOT")) {
            String timeLabel = new SimpleDateFormat("yyyyMMdd'.'HHmmss").format(new Date());
            versionLabel = versionLabel.replace("SNAPSHOT", timeLabel); // Append time to get unique version label
        }
        AWSCredentialsProvider credentialsProvider;
        if (deployment.getAccount()==null || deployment.getAccount().isEmpty()) {
            credentialsProvider = new AWSCredentialsProviderChain(new EnvironmentVariableCredentialsProvider(), new SystemPropertiesCredentialsProvider(), new ProfileCredentialsProvider(beanstalk.getProfile()));
        }else{
            credentialsProvider =  CredentialUtility.getAssumeRoleCredentials(deployment.getArnRole(), deployment.getAccount());
            log.info("Obtained credentials using arnRole {} for account {}", deployment.getArnRole() , deployment.getAccount());
        }
        String s3Endpoint = Utilities.coalesce(deployment.getS3Endpoint(),beanstalk.getS3Endpoint());
        String beanstalkEndpoint =Utilities.coalesce(deployment.getBeanstalkEndpoint(),beanstalk.getBeanstalkEndpoint());
        BeanstalkDeployer deployer = new BeanstalkDeployer(s3Endpoint, beanstalkEndpoint, credentialsProvider);
        File warFile = getProject().files(war).getSingleFile();
        deployer.deploy(warFile, deployment.getApplication(), deployment.getEnvironment(), deployment.getTemplate(), versionLabel);
    }

    public void setBeanstalk(BeanstalkPluginExtension beanstalk) {
        this.beanstalk = beanstalk;
    }

    @InputFiles
    public Object getWar() {
        return war;
    }

    public void setWar(Object war) {
        this.war = war;
    }

    public void setDeployment(BeanstalkDeployment deployment) {
        this.deployment = deployment;
        setWar(deployment.getWar());
    }
}
