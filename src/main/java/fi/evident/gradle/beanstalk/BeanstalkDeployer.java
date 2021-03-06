package fi.evident.gradle.beanstalk;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient;
import com.amazonaws.services.elasticbeanstalk.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

import static fi.evident.gradle.beanstalk.EncodingUtils.urlEncode;
import static java.util.Collections.emptyList;
import static java.util.Collections.sort;

public class BeanstalkDeployer {

    private final AmazonS3 s3;

    private final AWSElasticBeanstalk elasticBeanstalk;

    private static final int VERSIONS_TO_KEEP = 20;

    private static final Logger log = LoggerFactory.getLogger(BeanstalkDeployer.class);

    public BeanstalkDeployer(String s3Endpoint, String beanstalkEndpoint, AWSCredentialsProvider credentialsProvider) {
        s3 = new AmazonS3Client(credentialsProvider);
        elasticBeanstalk = new AWSElasticBeanstalkClient(credentialsProvider);
        s3.setEndpoint(s3Endpoint);
        elasticBeanstalk.setEndpoint(beanstalkEndpoint);
    }

    public void deploy(File warFile, String applicationName, String environmentName, String templateName, String versionLabel) {
        log.info("Starting deployment of {}", applicationName);

        S3Location bundle = uploadCodeBundle(warFile);
        ApplicationVersionDescription version = createApplicationVersion(bundle, applicationName, versionLabel);
        deployNewVersion(version.getVersionLabel(), environmentName, applicationName, templateName);
        deleteOldVersions(applicationName);
    }

    public void deleteOldVersions(String applicationName) {
        DescribeApplicationVersionsRequest search = new DescribeApplicationVersionsRequest();
        search.setApplicationName(applicationName);

        List<ApplicationVersionDescription> versions = elasticBeanstalk.describeApplicationVersions(search).getApplicationVersions();
        List<ApplicationVersionDescription> versionsToRemove = versionsToRemove(versions);
        Set<String> deployedLabels = findDeployedLabels(applicationName);

        log.info("Removing {} oldest versions of total {} versions", versionsToRemove.size(), versions.size());
        for (ApplicationVersionDescription version : versionsToRemove) {
            if (deployedLabels.contains(version.getVersionLabel())) {
                log.info("Not removing version {} because it is deployed", version.getVersionLabel());
            } else {
                deleteApplicationVersion(version);
            }
        }
    }

    private static List<ApplicationVersionDescription> versionsToRemove(List<ApplicationVersionDescription> versions) {
        int numberOfVersionsToRemove = versions.size() - VERSIONS_TO_KEEP;
        if (numberOfVersionsToRemove <= 0)
            return emptyList();

        ArrayList<ApplicationVersionDescription> result = new ArrayList<ApplicationVersionDescription>(versions);
        sort(result, new Comparator<ApplicationVersionDescription>() {
            @Override
            public int compare(ApplicationVersionDescription o1, ApplicationVersionDescription o2) {
                return o1.getDateUpdated().compareTo(o2.getDateUpdated());
            }
        });

        return result.subList(0, numberOfVersionsToRemove);
    }

    public void deleteApplicationVersion(ApplicationVersionDescription version) {
        log.info("Deleting application version {}", version.getVersionLabel());

        DeleteApplicationVersionRequest deleteRequest = new DeleteApplicationVersionRequest();
        deleteRequest.setApplicationName(version.getApplicationName());
        deleteRequest.setVersionLabel(version.getVersionLabel());
        deleteRequest.setDeleteSourceBundle(true);
        elasticBeanstalk.deleteApplicationVersion(deleteRequest);
    }

    private void deployNewVersion(String versionLabel, String environmentName, String applicationName, String templateName) {
        log.info("Describe environments to check if environment exists");

        ArrayList<String> environmentNames = new ArrayList<String>();
        environmentNames.add(environmentName);
        DescribeEnvironmentsRequest describeEnvironmentsRequest = new DescribeEnvironmentsRequest();
        describeEnvironmentsRequest.setEnvironmentNames(environmentNames);

        DescribeEnvironmentsResult describeEnvironmentsResult = elasticBeanstalk.describeEnvironments(describeEnvironmentsRequest);
        if (describeEnvironmentsResult.getEnvironments().size() == 0) {
            log.info("Create environment with uploaded application version");

            CreateEnvironmentRequest createEnvironmentRequest = new CreateEnvironmentRequest();
            createEnvironmentRequest.setApplicationName(applicationName);
            createEnvironmentRequest.setEnvironmentName(environmentName);
            createEnvironmentRequest.setTemplateName(templateName);
            createEnvironmentRequest.setVersionLabel(versionLabel);

            CreateEnvironmentResult createEnvironmentResult = elasticBeanstalk.createEnvironment(createEnvironmentRequest);
            log.info("Created environment {}", createEnvironmentResult);
        } else {
            log.info("Update environment with uploaded application version");

            UpdateEnvironmentRequest updateEnvironmentRequest = new UpdateEnvironmentRequest();
            updateEnvironmentRequest.setEnvironmentName(environmentName);
            updateEnvironmentRequest.setVersionLabel(versionLabel);

            UpdateEnvironmentResult updateEnvironmentResult = elasticBeanstalk.updateEnvironment(updateEnvironmentRequest);
            log.info("Updated environment {}", updateEnvironmentResult);
        }
    }

    private ApplicationVersionDescription createApplicationVersion(S3Location bundle, String applicationName, String versionLabel) {
        log.info("Create application version {} with for application {}", versionLabel, applicationName);

        CreateApplicationVersionRequest createApplicationVersionRequest = new CreateApplicationVersionRequest(applicationName, versionLabel);
        createApplicationVersionRequest.setDescription(applicationName + " via Gradle deployment on " + Instant.now());
        createApplicationVersionRequest.setAutoCreateApplication(true);
        createApplicationVersionRequest.setSourceBundle(bundle);

        CreateApplicationVersionResult createApplicationVersionResult = elasticBeanstalk.createApplicationVersion(createApplicationVersionRequest);
        log.info("Registered application version {}", createApplicationVersionResult);
        return createApplicationVersionResult.getApplicationVersion();
    }

    private S3Location uploadCodeBundle(File warFile) {
        if (!warFile.exists())
            throw new RuntimeException("war-file " + warFile + " does not exist.");

        log.info("Uploading {} to Amazon S3", warFile);

        String bucketName = elasticBeanstalk.createStorageLocation().getS3Bucket();
        String key = urlEncode(warFile.getName());

        s3.putObject(bucketName, key, warFile);

        return new S3Location(bucketName, key);
    }

    private Set<String> findDeployedLabels(String applicationName) {
        DescribeEnvironmentsRequest req = new DescribeEnvironmentsRequest();
        req.setApplicationName(applicationName);

        Set<String> result = new TreeSet<String>();
        for (EnvironmentDescription description : elasticBeanstalk.describeEnvironments(req).getEnvironments()) {
            if (description.getVersionLabel() != null) { // Ignore null value (for example when environment is launching)
                result.add(description.getVersionLabel());
            }
        }
        return result;
    }
}
