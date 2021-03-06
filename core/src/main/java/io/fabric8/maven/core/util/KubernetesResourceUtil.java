/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.core.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.extensions.Templates;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Job;
import io.fabric8.kubernetes.api.model.JobSpec;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerSpec;
import io.fabric8.kubernetes.api.model.extensions.DaemonSet;
import io.fabric8.kubernetes.api.model.extensions.DaemonSetSpec;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpec;
import io.fabric8.kubernetes.api.model.extensions.ReplicaSet;
import io.fabric8.kubernetes.api.model.extensions.ReplicaSetSpec;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetSpec;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.internal.HasMetadataComparator;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.docker.util.ImageName;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildStatus;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigSpec;
import io.fabric8.openshift.api.model.Template;
import io.fabric8.utils.Files;
import io.fabric8.utils.Strings;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;

import static io.fabric8.kubernetes.api.KubernetesHelper.getName;
import static io.fabric8.kubernetes.api.KubernetesHelper.parseDate;
import static io.fabric8.maven.core.util.Constants.RESOURCE_APP_CATALOG_ANNOTATION;
import static io.fabric8.maven.core.util.Constants.RESOURCE_SOURCE_URL_ANNOTATION;
import static io.fabric8.utils.Strings.isNullOrBlank;

/**
 * Utility class for handling Kubernetes resource descriptors
 *
 * @author roland
 * @since 02/05/16
 */
public class KubernetesResourceUtil {

    public static final String API_VERSION = "v1";
    public static final String API_EXTENSIONS_VERSION = "extensions/v1beta1";
    public static final String API_APPS_VERSION = "apps/v1beta1";
    public static final ResourceVersioning DEFAULT_RESOURCE_VERSIONING = new ResourceVersioning()
            .withCoreVersion(API_VERSION)
            .withExtensionsVersion(API_EXTENSIONS_VERSION)
            .withAppsVersion(API_APPS_VERSION);

    public static final HashSet<Class<?>> SIMPLE_FIELD_TYPES = new HashSet<>();


    /**
     * Read all Kubernetes resource fragments from a directory and create a {@link KubernetesListBuilder} which
     * can be adapted later.
     *
     * @param apiVersions the api versions to use
     * @param defaultName the default name to use when none is given
     * @param resourceFiles files to add.
     * @return the list builder
     * @throws IOException
     */
    public static KubernetesListBuilder readResourceFragmentsFrom(ResourceVersioning apiVersions,
                                                                  String defaultName,
                                                                  File[] resourceFiles) throws IOException {
        KubernetesListBuilder builder = new KubernetesListBuilder();
        if (resourceFiles != null) {
            for (File file : resourceFiles) {
                HasMetadata resource = getResource(apiVersions, file, defaultName);
                builder.addToItems(resource);
            }
        }
        return builder;
    }

    /**
     * Read a Kubernetes resource fragment and add meta information extracted from the filename
     * to the resource descriptor. I.e. the following elements are added if not provided in the fragment:
     *
     * <ul>
     *     <li>name - Name of the resource added to metadata</li>
     *     <li>kind - Resource's kind</li>
     *     <li>apiVersion - API version (given as parameter to this method)</li>
     * </ul>
     *
     *
     * @param apiVersions the API versions to add if not given.
     * @param file file to read, whose name must match {@link #FILENAME_PATTERN}.  @return map holding the fragment
     * @param appName resource name specifying resources belonging to this application
     */
    public static HasMetadata getResource(ResourceVersioning apiVersions,
                                          File file, String appName) throws IOException {
        Map<String,Object> fragment = readAndEnrichFragment(apiVersions, file, appName);
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.convertValue(fragment, HasMetadata.class);
        } catch (ClassCastException exp) {
            throw new IllegalArgumentException(String.format("Resource fragment %s has an invalid syntax (%s)", file.getPath(), exp.getMessage()));
        }
    }

    public static String toYaml(Object resource) throws JsonProcessingException {
        return serializeAsString(resource, ResourceFileType.yaml);
    }

    public static String toJson(Object resource) throws JsonProcessingException {
        return serializeAsString(resource, ResourceFileType.json);
    }

    public static File writeResource(Object resource, File target, ResourceFileType resourceFileType) throws IOException {
        File outputFile = resourceFileType.addExtension(target);
        return writeResourceFile(resource, outputFile, resourceFileType);
    }

    public static File writeResourceFile(Object resource, File outputFile, ResourceFileType resourceFileType) throws IOException {
        String serialized = serializeAsString(resource, resourceFileType);
        Files.writeToFile(outputFile, serialized, Charset.defaultCharset());
        return outputFile;
    }

    private static String serializeAsString(Object resource, ResourceFileType resourceFileType) throws JsonProcessingException {
        ObjectMapper mapper = resourceFileType.getObjectMapper()
                                              .enable(SerializationFeature.INDENT_OUTPUT)
                                              .disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS)
                                              .disable(SerializationFeature.WRITE_NULL_MAP_VALUES);
        return mapper.writeValueAsString(resource);
    }

    public static File[] listResourceFragments(File resourceDir) {
        final Pattern filenamePattern = Pattern.compile(FILENAME_PATTERN);
        final Pattern exludePattern = Pattern.compile(PROFILES_PATTERN);
        return resourceDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return filenamePattern.matcher(name).matches() && !exludePattern.matcher(name).matches();
            }
        });
    }


    // ========================================================================================================

    private final static Map<String,String> FILENAME_TO_KIND_MAPPER = new HashMap<>();
    private final static Map<String,String> KIND_TO_FILENAME_MAPPER = new HashMap<>();
    private static String mappings[] =
        {
            // lets put the abbreviation we want to use first
            "cm", "ConfigMap",
            "configmap", "ConfigMap",
            "cronjob", "CronJob",
            "cr", "ClusterRole",
            "crole", "ClusterRole",
            "clusterrole", "ClusterRole",
            "crb", "ClusterRoleBinding",
            "clusterrb", "ClusterRoleBinding",
            "cj", "CronJob",
            "deployment", "Deployment",
            "is", "ImageStream",
            "istag", "ImageStreamTag",
            "lr", "LimitRange",
            "limitrange", "LimitRange",
            "ns", "Namespace",
            "namespace", "Namespace",
            "oauthclient", "OAuthClient",
            "pb", "PolicyBinding",
            "pv", "PersistentVolume",
            "pvc", "PersistentVolumeClaim",
            "project", "Project",
            "pr", "ProjectRequest",
            "rq", "ResourceQuota",
            "resourcequota", "ResourceQuota",
            "role", "Role",
            "rb", "RoleBinding",
            "rolebinding", "RoleBinding",
            "rbr", "RoleBindingRestriction",
            "rolebindingrestriction", "RoleBindingRestriction",
            "secret", "Secret",
            "service", "Service",
            "svc", "Service",
            "sa", "ServiceAccount",
            "rc", "ReplicationController",
            "rs", "ReplicaSet",
            "daemonset", "DaemonSet",
            "ds", "DaemonSet",
            "statefulset", "StatefulSet",

            // OpenShift Resources:
            "bc", "BuildConfig",
            "dc", "DeploymentConfig",
            "deploymentconfig", "DeploymentConfig",
            "route", "Route",
            "template", "Template",
        };

    static {
        for (int i = 0; i < mappings.length; i+=2) {
            FILENAME_TO_KIND_MAPPER.put(mappings[i], mappings[i+1]);
            KIND_TO_FILENAME_MAPPER.put(mappings[i+1], mappings[i]);
        }
    }

    private static final String FILENAME_PATTERN = "^(?<name>.*?)(-(?<type>[^-]+))?\\.(?<ext>yaml|yml|json)$";
    private static final String PROFILES_PATTERN = "^profiles?\\.ya?ml$";

    // Read fragment and add default values
    private static Map<String, Object> readAndEnrichFragment(ResourceVersioning apiVersions,
                                                             File file, String appName) throws IOException {
        Pattern pattern = Pattern.compile(FILENAME_PATTERN, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(file.getName());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                String.format("Resource file name '%s' does not match pattern <name>-<type>.(yaml|yml|json)", file.getName()));
        }
        String name = matcher.group("name");
        String type = matcher.group("type");
        String ext = matcher.group("ext").toLowerCase();
        String kind;

        Map<String,Object> fragment = readFragment(file, ext);

        if (type != null) {
            kind = getAndValidateKindFromType(file, type);
        } else {
            // Try name as type
            kind = FILENAME_TO_KIND_MAPPER.get(name.toLowerCase());
            if (kind != null) {
                // Name is in fact the type, so lets erase the name.
                name = null;
            }
        }

        addKind(fragment, kind, file.getName());

        String apiVersion = apiVersions.getCoreVersion();
        if (Objects.equals(kind, "Deployment") || Objects.equals(kind, "Ingress")) {
            apiVersion = apiVersions.getExtensionsVersion();
        } else if (Objects.equals(kind, "StatefulSet")) {
            apiVersion = apiVersions.getAppsVersion();
        }
        addIfNotExistent(fragment, "apiVersion", apiVersion);

        Map<String, Object> metaMap = getMetadata(fragment);
        // No name means: generated app name should be taken as resource name
        addIfNotExistent(metaMap, "name", StringUtils.isNotBlank(name) ? name : appName);

        return fragment;
    }

    private static String getAndValidateKindFromType(File file, String type) {
        String kind;
        kind = FILENAME_TO_KIND_MAPPER.get(type.toLowerCase());
        if (kind == null) {
            throw new IllegalArgumentException(
                String.format("Unknown type '%s' for file %s. Must be one of : %s",
                              type, file.getName(), StringUtils.join(FILENAME_TO_KIND_MAPPER.keySet().iterator(), ", ")));
        }
        return kind;
    }

    private static void addKind(Map<String, Object> fragment, String kind, String fileName) {
        if (kind == null && !fragment.containsKey("kind")) {
            throw new IllegalArgumentException(
                "No type given as part of the file name (e.g. 'app-rc.yml') " +
                "and no 'Kind' defined in resource descriptor " + fileName);
        }
        addIfNotExistent(fragment, "kind", kind);
    }

    // ===============================================================================================

    private static Map<String, Object> getMetadata(Map<String, Object> fragment) {
        Object mo = fragment.get("metadata");
        Map<String, Object> meta;
        if (mo == null) {
            meta = new HashMap<>();
            fragment.put("metadata", meta);
            return meta;
        } else if (mo instanceof Map) {
            return (Map<String, Object>) mo;
        } else {
            throw new IllegalArgumentException("Metadata is expected to be a Map, not a " + mo.getClass());
        }
    }

    private static void addIfNotExistent(Map<String, Object> fragment, String key, String value) {
        if (!fragment.containsKey(key)) {
            fragment.put(key, value);
        }
    }

    private static Map<String,Object> readFragment(File file, String ext) throws IOException {
        ObjectMapper mapper = new ObjectMapper("json".equals(ext) ? new JsonFactory() : new YAMLFactory());
        TypeReference<HashMap<String,Object>> typeRef = new TypeReference<HashMap<String,Object>>() {};
        try {
            Map<String, Object> ret = mapper.readValue(file, typeRef);
            return ret != null ? ret : new HashMap<String, Object>();
        } catch (JsonProcessingException e) {
            throw new JsonMappingException(String.format("[%s] %s", file, e.getMessage()), e.getLocation(), e);
        }
    }

    public static String getNameWithSuffix(String name, String kind) {
        String suffix =  KIND_TO_FILENAME_MAPPER.get(kind);
        return suffix != null ? name +  "-" + suffix : name;
    }

    public static String extractContainerName(MavenProject project, ImageConfiguration imageConfig) {
        String alias = imageConfig.getAlias();
        return alias != null ? alias : extractImageUser(imageConfig.getName(), project) + "-" + project.getArtifactId();
    }

    private static String extractImageUser(String image, MavenProject project) {
        ImageName name = new ImageName(image);
        String imageUser = name.getUser();
        return imageUser != null ? imageUser : project.getGroupId();
    }

    public static Map<String, String> removeVersionSelector(Map<String, String> selector) {
        Map<String, String> answer = new HashMap<>(selector);
        answer.remove("version");
        return answer;
    }

    public static boolean checkForKind(KubernetesListBuilder builder, String... kinds) {
        Set<String> kindSet = new HashSet<>(Arrays.asList(kinds));
        for (HasMetadata item : builder.getItems()) {
            if (kindSet.contains(item.getKind())) {
                return true;
            }
        }
        return false;
    }

    public static boolean addPort(List<ContainerPort> ports, String portNumberText, String portName, Logger log) {
        if (Strings.isNullOrBlank(portNumberText)) {
            return false;
        }
        int portValue;
        try {
            portValue = Integer.parseInt(portNumberText);
        } catch (NumberFormatException e) {
            log.warn("Could not parse remote debugging port %s as an integer: %s", portNumberText, e);
            return false;
        }
        for (ContainerPort port : ports) {
            String name = port.getName();
            Integer containerPort = port.getContainerPort();
            if (containerPort != null && containerPort.intValue() == portValue) {
                return false;
            }
        }
        ports.add(new ContainerPortBuilder().withName(portName).withContainerPort(portValue).build());
        return true;
    }

    /**
     * Try to set an environment variable in the list or return the old value
     * if present and different from the current one.
     *
     * Environment variables will not be overridden.
     *
     * @param envVarList the list of environment variables
     * @param name the environment variable
     * @param value the value to set
     * @return the old value, if present, or null
     */
    public static EnvVar setEnvVarNoOverride(List<EnvVar> envVarList, String name, String value) {
        for (EnvVar envVar : envVarList) {
            String envVarName = envVar.getName();
            if (io.fabric8.utils.Objects.equal(name, envVarName)) {
                String oldValue = envVar.getValue();
                if (io.fabric8.utils.Objects.equal(value, oldValue)) {
                    return null; // identical values
                }
                return envVar;
            }
        }
        EnvVar env = new EnvVarBuilder().withName(name).withValue(value).build();
        envVarList.add(env);
        return null;
    }

    public static boolean setEnvVar(List<EnvVar> envVarList, String name, String value) {
        for (EnvVar envVar : envVarList) {
            String envVarName = envVar.getName();
            if (io.fabric8.utils.Objects.equal(name, envVarName)) {
                String oldValue = envVar.getValue();
                if (io.fabric8.utils.Objects.equal(value, oldValue)) {
                    return false;
                } else {
                    envVar.setValue(value);
                    return true;
                }
            }
        }
        EnvVar env = new EnvVarBuilder().withName(name).withValue(value).build();
        envVarList.add(env);
        return true;
    }

    public static String getEnvVar(List<EnvVar> envVarList, String name, String defaultValue) {
        String answer = defaultValue;
        if (envVarList != null) {
            for (EnvVar envVar : envVarList) {
                String envVarName = envVar.getName();
                if (io.fabric8.utils.Objects.equal(name, envVarName)) {
                    String value = envVar.getValue();
                    if (Strings.isNotBlank(value)) {
                        return value;
                    }
                }
            }
        }
        return answer;
    }

    public static void validateKubernetesMasterUrl(URL masterUrl) throws MojoExecutionException {
        if (masterUrl == null || Strings.isNullOrBlank(masterUrl.toString())) {
            throw new MojoExecutionException("Cannot find Kubernetes master URL. Have you started a cluster via `mvn fabric8:cluster-start` or connected to a remote cluster via `kubectl`?");
        }
    }

    public static void handleKubernetesClientException(KubernetesClientException e, Logger logger) throws MojoExecutionException {
        Throwable cause = e.getCause();
        if (cause instanceof UnknownHostException) {
            logger.error("Could not connect to kubernetes cluster!");
            logger.error("Have you started a local cluster via `mvn fabric8:cluster-start` or connected to a remote cluster via `kubectl`?");
            logger.info("For more help see: http://fabric8.io/guide/getStarted/");
            logger.error("Connection error: %s", cause);

            String message = "Could not connect to kubernetes cluster. Have you started a cluster via `mvn fabric8:cluster-start` or connected to a remote cluster via `kubectl`? Error: " + cause;
            throw new MojoExecutionException(message, e);
        } else {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Returns the resource of the given kind and name from the collection or null
     */
    public static <T> T findResourceByName(Iterable<HasMetadata> entities, Class<T> clazz, String name) {
        if (entities != null) {
            for (HasMetadata entity : entities) {
                if (clazz.isInstance(entity) && Objects.equals(name, getName(entity))) {
                    return clazz.cast(entity);
                }
            }
        }
        return null;
    }

    public static String getBuildStatusPhase(Build build) {
        String status = null;
        BuildStatus buildStatus = build.getStatus();
        if (buildStatus != null) {
            status = buildStatus.getPhase();
        }
        return status;
    }

    public static String getBuildStatusReason(Build build) {
        BuildStatus buildStatus = build.getStatus();
        if (buildStatus != null) {
            String reason = buildStatus.getReason();
            String phase = buildStatus.getPhase();
            if (Strings.isNotBlank(phase)) {
                if (Strings.isNotBlank(reason)) {
                    return phase + ": " + reason;
                } else {
                    return phase;
                }
            } else {
                return Strings.defaultIfEmpty(reason, "");
            }
        }
        return "";
    }

    public static Pod getNewestPod(Collection<Pod> pods) {
        if (pods == null || pods.isEmpty()) {
            return null;
        }
        List<Pod> sortedPods = new ArrayList<>(pods);
        Collections.sort(sortedPods, new Comparator<Pod>() {
            @Override
            public int compare(Pod p1, Pod p2) {
                Date t1 = getCreationTimestamp(p1);
                Date t2 = getCreationTimestamp(p2);
                if (t1 != null) {
                    if (t2 == null) {
                        return 1;
                    } else {
                        return t1.compareTo(t2);
                    }
                } else if (t2 == null) {
                    return 0;
                }
                return -1;
            }
        });
        return sortedPods.get(sortedPods.size() - 1);
    }

    public static Date getCreationTimestamp(HasMetadata hasMetadata) {
        ObjectMeta metadata = hasMetadata.getMetadata();
        if (metadata != null) {
            return parseTimestamp(metadata.getCreationTimestamp());
        }
        return null;
    }

    private static Date parseTimestamp(String text) {
        if (text == null) {
            return null;
        }
        return parseDate(text);
    }

    public static boolean podHasContainerImage(Pod pod, String imageName) {
        if (pod != null) {
            PodSpec spec = pod.getSpec();
            if (spec != null) {
                List<Container> containers = spec.getContainers();
                if (containers != null) {
                    for (Container container : containers) {
                        if (io.fabric8.utils.Objects.equal(imageName, container.getImage())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static String getDockerContainerID(Pod pod) {
        PodStatus status = pod.getStatus();
        if (status != null) {
            List<ContainerStatus> containerStatuses = status.getContainerStatuses();
            if (containerStatuses != null) {
                for (ContainerStatus containerStatus : containerStatuses) {
                    String containerID = containerStatus.getContainerID();
                    if (Strings.isNotBlank(containerID)) {
                        String prefix = "://";
                        int idx = containerID.indexOf(prefix);
                        if (idx > 0) {
                            return containerID.substring(idx + prefix.length());
                        }
                        return containerID;
                    }
                }
            }
        }
        return null;
    }

    public static boolean isNewerResource(HasMetadata newer, HasMetadata older) {
        Date t1 = getCreationTimestamp(newer);
        Date t2 = getCreationTimestamp(older);
        if (t1 != null) {
            return t2 == null || t1.compareTo(t2) > 0;
        }
        return false;
    }

    /**
     * Uses reflection to copy over default values from the defaultValues object to the targetValues
     * object similar to the following:
     *
     * <code>
\    * if( values.get${FIELD}() == null ) {
     *   values.(with|set){FIELD}(defaultValues.get${FIELD});
     * }
     * </code>
     *
     * Only fields that which use primitives, boxed primitives, or String object are copied.
     *
     * @param targetValues
     * @param defaultValues
     */
    public static void mergeSimpleFields(Object targetValues, Object defaultValues) {
        Class<?> tc = targetValues.getClass();
        Class<?> sc = defaultValues.getClass();
        for (Method targetGetMethod : tc.getMethods()) {
            if (!targetGetMethod.getName().startsWith("get")) {
                continue;
            }

            Class<?> fieldType = targetGetMethod.getReturnType();
            if (!SIMPLE_FIELD_TYPES.contains(fieldType)) {
                continue;
            }

            String fieldName = targetGetMethod.getName().substring(3);
            Method withMethod = null;
            try {
                withMethod = tc.getMethod("with" + fieldName, fieldType);
            } catch (NoSuchMethodException e) {
                try {
                    withMethod = tc.getMethod("set" + fieldName, fieldType);
                } catch (NoSuchMethodException e2) {
                    continue;
                }
            }

            Method sourceGetMethod = null;
            try {
                sourceGetMethod = sc.getMethod("get" + fieldName);
            } catch (NoSuchMethodException e) {
                continue;
            }

            try {
                if (targetGetMethod.invoke(targetValues) == null) {
                    withMethod.invoke(targetValues, sourceGetMethod.invoke(defaultValues));
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            }
        }
    }

    public static void mergePodSpec(PodSpecBuilder builder, PodSpec defaultPodSpec, String defaultName) {
        List<Container> containers = builder.getContainers();
        List<Container> defaultContainers = defaultPodSpec.getContainers();
        int size = defaultContainers.size();
        if (size > 0) {
            if (containers == null || containers.isEmpty()) {
                builder.addToContainers(defaultContainers.toArray(new Container[size]));
            } else {
                int idx = 0;
                for (Container defaultContainer : defaultContainers) {
                    Container container;
                    if (idx < containers.size()) {
                        container = containers.get(idx);
                    } else {
                        container = new Container();
                        containers.add(container);
                    }
                    mergeSimpleFields(container, defaultContainer);
                    List<EnvVar> defaultEnv = defaultContainer.getEnv();
                    if (defaultEnv != null) {
                        for (EnvVar envVar : defaultEnv) {
                            ensureHasEnv(container, envVar);
                        }
                    }
                    List<ContainerPort> defaultPorts = defaultContainer.getPorts();
                    if (defaultPorts != null) {
                        for (ContainerPort port : defaultPorts) {
                            ensureHasPort(container, port);
                        }
                    }
                    if (container.getReadinessProbe() == null) {
                        container.setReadinessProbe(defaultContainer.getReadinessProbe());
                    }
                    if (container.getLivenessProbe() == null) {
                        container.setLivenessProbe(defaultContainer.getLivenessProbe());
                    }
                    if (container.getSecurityContext() == null) {
                        container.setSecurityContext(defaultContainer.getSecurityContext());
                    }
                    idx++;
                }
                builder.withContainers(containers);
            }
        } else if (!containers.isEmpty()) {
            // lets default the container name if there's none specified in the custom yaml file
            Container container = containers.get(0);
            if (isNullOrBlank(container.getName())) {
                container.setName(defaultName);
            }
            builder.withContainers(containers);
        }
    }

    private static void ensureHasEnv(Container container, EnvVar envVar) {
        List<EnvVar> envVars = container.getEnv();
        if (envVars == null) {
            envVars = new ArrayList<>();
            container.setEnv(envVars);
        }
        for (EnvVar var : envVars) {
            if (Objects.equals(var.getName(), envVar.getName())) {
                return;
            }
        }
        envVars.add(envVar);
    }

    private static void ensureHasPort(Container container, ContainerPort port) {
        List<ContainerPort> ports = container.getPorts();
        if (ports == null) {
            ports = new ArrayList<>();
            container.setPorts(ports);
        }
        for (ContainerPort cp : ports) {
            String n1 = cp.getName();
            String n2 = port.getName();
            if (n1 != null && n2 != null && n1.equals(n2)) {
                return;
            }
            Integer p1 = cp.getContainerPort();
            Integer p2 = port.getContainerPort();
            if (p1 != null && p2 != null && p1.intValue() == p2.intValue()) {
                return;
            }
        }
        ports.add(port);
    }

    public static String getSourceUrlAnnotation(HasMetadata item) {
        return KubernetesHelper.getOrCreateAnnotations(item).get(RESOURCE_SOURCE_URL_ANNOTATION);
    }

    public static void setSourceUrlAnnotationIfNotSet(HasMetadata item, String sourceUrl) {
        Map<String, String> annotations = KubernetesHelper.getOrCreateAnnotations(item);
        if (!annotations.containsKey(RESOURCE_SOURCE_URL_ANNOTATION)) {
            annotations.put(RESOURCE_SOURCE_URL_ANNOTATION, sourceUrl);
            item.getMetadata().setAnnotations(annotations);
        }
    }

    public static boolean isAppCatalogResource(HasMetadata templateOrConfigMap) {
        String catalogAnnotation = KubernetesHelper.getOrCreateAnnotations(templateOrConfigMap).get(RESOURCE_APP_CATALOG_ANNOTATION);
        return "true".equals(catalogAnnotation);
    }

    public static Set<HasMetadata> loadResources(File manifest) throws IOException {
        Object dto = KubernetesHelper.loadYaml(manifest, KubernetesResource.class);
        if (dto == null) {
            throw new IllegalStateException("Cannot load kubernetes YAML: " + manifest);
        }

        if (dto instanceof Template) {
            Template template = (Template) dto;
            boolean failOnMissingParameterValue = false;
            dto = Templates.processTemplatesLocally(template, failOnMissingParameterValue);
        }

        Set<KubernetesResource<?>> resources = new LinkedHashSet<>();

        Set<HasMetadata> entities = new TreeSet<>(new HasMetadataComparator());
        for (KubernetesResource<?> resource : resources) {
            entities.addAll(KubernetesHelper.toItemList(resource));
        }

        entities.addAll(KubernetesHelper.toItemList(dto));
        return entities;
    }

    public static LabelSelector getPodLabelSelector(Set<HasMetadata> entities) {
        LabelSelector chosenSelector = null;
        for (HasMetadata entity : entities) {
            LabelSelector selector = getPodLabelSelector(entity);
            if (selector != null) {
                if (chosenSelector != null && !chosenSelector.equals(selector)) {
                    throw new IllegalArgumentException("Multiple selectors found for the given entities: " + chosenSelector + " - " + selector);
                }
                chosenSelector = selector;
            }
        }
        return chosenSelector;
    }

    public static LabelSelector getPodLabelSelector(HasMetadata entity) {
        LabelSelector selector = null;
        if (entity instanceof Deployment) {
            Deployment resource = (Deployment) entity;
            DeploymentSpec spec = resource.getSpec();
            if (spec != null) {
                selector = spec.getSelector();
            }
        } else if (entity instanceof ReplicaSet) {
            ReplicaSet resource = (ReplicaSet) entity;
            ReplicaSetSpec spec = resource.getSpec();
            if (spec != null) {
                selector = spec.getSelector();
            }
        } else if (entity instanceof DeploymentConfig) {
            DeploymentConfig resource = (DeploymentConfig) entity;
            DeploymentConfigSpec spec = resource.getSpec();
            if (spec != null) {
                selector = toLabelSelector(spec.getSelector());
            }
        } else if (entity instanceof ReplicationController) {
            ReplicationController resource = (ReplicationController) entity;
            ReplicationControllerSpec spec = resource.getSpec();
            if (spec != null) {
                selector = toLabelSelector(spec.getSelector());
            }
        } else if (entity instanceof DaemonSet) {
            DaemonSet resource = (DaemonSet) entity;
            DaemonSetSpec spec = resource.getSpec();
            if (spec != null) {
                selector = spec.getSelector();
            }
        } else if (entity instanceof StatefulSet) {
            StatefulSet resource = (StatefulSet) entity;
            StatefulSetSpec spec = resource.getSpec();
            if (spec != null) {
                selector = spec.getSelector();
            }
        } else if (entity instanceof Job) {
            Job resource = (Job) entity;
            JobSpec spec = resource.getSpec();
            if (spec != null) {
                selector = spec.getSelector();
            }
        }
        return selector;
    }

    private static LabelSelector toLabelSelector(Map<String, String> matchLabels) {
        if (matchLabels != null && !matchLabels.isEmpty()) {
            return new LabelSelectorBuilder().withMatchLabels(matchLabels).build();
        }
        return null;
    }

}
