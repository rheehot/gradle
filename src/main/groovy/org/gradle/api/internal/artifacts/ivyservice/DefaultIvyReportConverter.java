/*
 * Copyright 2007-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice;

import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DependencyArtifactDescriptor;
import org.apache.ivy.core.module.id.ArtifactRevisionId;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.ConfigurationResolveReport;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.IvyNodeCallers;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.internal.artifacts.DefaultResolvedDependency;
import org.gradle.util.Clock;
import org.gradle.util.GUtil;
import org.gradle.util.WrapUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * @author Hans Dockter
 */
public class DefaultIvyReportConverter implements IvyReportConverter {
    private static Logger logger = LoggerFactory.getLogger(DefaultIvyReportConverter.class);

    public Set<File> getClasspath(String configuration, ResolveReport resolveReport) {
        Clock clock = new Clock();
        Set<File> classpath = new LinkedHashSet<File>();
        for (ArtifactDownloadReport artifactDownloadReport : getAllArtifactReports(resolveReport, configuration)) {
            classpath.add(artifactDownloadReport.getLocalFile());
        }
        logger.debug("Timing: Translating report for configuration {} took {}", configuration, clock.getTime());
        return classpath;
    }

    private ArtifactDownloadReport[] getAllArtifactReports(ResolveReport report, String conf) {
        logger.debug("using internal report instance to get artifacts list");
        ConfigurationResolveReport configurationReport = report.getConfigurationReport(conf);
        if (configurationReport == null) {
            throw new GradleException("bad confs provided: " + conf
                    + " not found among " + Arrays.asList(report.getConfigurations()));
        }
        return configurationReport.getArtifactsReports(null, false);
    }

    public Map<Dependency, Set<ResolvedDependency>> translateReport(ResolveReport resolveReport, Configuration configuration) {
        Clock clock = new Clock();
        Map<Dependency, Set<ResolvedDependency>> firstLevelResolvedDependencies = new LinkedHashMap<Dependency, Set<ResolvedDependency>>();
        ConfigurationResolveReport configurationResolveReport = resolveReport.getConfigurationReport(configuration.getName());
        LinkedHashMap<ModuleRevisionId, Map<String, DefaultResolvedDependency>> handledNodes = new LinkedHashMap<ModuleRevisionId, Map<String, DefaultResolvedDependency>>();
        Map<DefaultResolvedDependency, IvyNode> resolvedDependencies2Nodes = new HashMap<DefaultResolvedDependency, IvyNode>();
        Map<ModuleRevisionId, Map<String, ModuleDependency>> firstLevelDependenciesModuleRevisionIds = createFirstLevelDependenciesModuleRevisionIds(configuration.getAllDependencies(ModuleDependency.class));
        List nodes = resolveReport.getDependencies();
        for (Iterator iterator = nodes.iterator(); iterator.hasNext();) {
            IvyNode node = (IvyNode) iterator.next();
            if (!isResolvedNode(node, configuration)) {
                continue;
            }
            getResolvedDependenciesForNode(node,
                    handledNodes,
                    resolvedDependencies2Nodes,
                    firstLevelResolvedDependencies,
                    configuration.getName(),
                    configurationResolveReport,
                    firstLevelDependenciesModuleRevisionIds,
                    resolveReport);
        }
        logger.debug("Timing: Translating report for configuration {} took {}", configuration, clock.getTime());
        return firstLevelResolvedDependencies;
    }

    private boolean isResolvedNode(IvyNode node, Configuration configuration) {
        return node.isLoaded() && !node.isEvicted(configuration.getName());
    }

    private Map<String, DefaultResolvedDependency> getResolvedDependenciesForNode(IvyNode ivyNode,
                                                                           Map<ModuleRevisionId, Map<String, DefaultResolvedDependency>> handledNodes,
                                                                           Map<DefaultResolvedDependency, IvyNode> resolvedDependencies2Nodes,
                                                                           Map<Dependency, Set<ResolvedDependency>> firstLevelResolvedDependencies, String conf,
                                                                           ConfigurationResolveReport configurationResolveReport,
                                                                           Map<ModuleRevisionId, Map<String, ModuleDependency>> firstLevelDependenciesModuleRevisionIds, ResolveReport resolveReport) {
        ModuleRevisionId nodeId = ivyNode.getId();
        if (handledNodes.containsKey(nodeId)) {
            return handledNodes.get(nodeId);
        }
        Map<String, DefaultResolvedDependency> resolvedDependencies = new LinkedHashMap<String, DefaultResolvedDependency>();
        for (IvyNodeCallers.Caller caller : ivyNode.getCallers(conf)) {
            Set<String> dependencyConfigurationsForNode = getDependencyConfigurationsByCaller(ivyNode, caller);
            for (String dependencyConfiguration : dependencyConfigurationsForNode) {
                DefaultResolvedDependency resolvedDependencyForDependencyConfiguration = resolvedDependencies.get(dependencyConfiguration);
                if (resolvedDependencyForDependencyConfiguration == null) {
                    resolvedDependencyForDependencyConfiguration = createResolvedDependency(ivyNode, resolveReport, dependencyConfiguration);
                    resolvedDependencies.put(dependencyConfiguration, resolvedDependencyForDependencyConfiguration);
                    resolvedDependencies2Nodes.put(resolvedDependencyForDependencyConfiguration, ivyNode);
                    addNodeIfFirstLevelDependency(ivyNode, resolvedDependencyForDependencyConfiguration, firstLevelDependenciesModuleRevisionIds, firstLevelResolvedDependencies);
                }
            }
            if (isRootCaller(configurationResolveReport, caller)) {
                for (DefaultResolvedDependency resolvedDependency : resolvedDependencies.values()) {
                    resolvedDependency.getParents().add(null);
                    resolvedDependency.addParentSpecificFiles(null, getFilesForParent(conf, ivyNode.getRoot(), caller, resolveReport));
                }
                continue;
            }
            IvyNode parentNode = configurationResolveReport.getDependency(caller.getModuleRevisionId());
            Map<String, DefaultResolvedDependency> parentResolvedDependencies = getResolvedDependenciesForNode(parentNode, handledNodes, resolvedDependencies2Nodes,
                    firstLevelResolvedDependencies, conf, configurationResolveReport,
                    firstLevelDependenciesModuleRevisionIds, resolveReport);
            createAssociationsBetweenChildAndParentResolvedDependencies(ivyNode, resolvedDependencies2Nodes, resolvedDependencies, parentNode, caller,
                    dependencyConfigurationsForNode, parentResolvedDependencies, resolveReport);
        }
        handledNodes.put(nodeId, resolvedDependencies);
        return resolvedDependencies;
    }

    private void createAssociationsBetweenChildAndParentResolvedDependencies(IvyNode ivyNode, Map<DefaultResolvedDependency, IvyNode> resolvedDependencies2Nodes, Map<String, DefaultResolvedDependency> resolvedDependencies,
                                                                             IvyNode callerNode, IvyNodeCallers.Caller caller, Set<String> dependencyConfigurationsForNode, Map<String, DefaultResolvedDependency> parentResolvedDependencies, ResolveReport resolveReport) {
        for (String dependencyConfiguration : dependencyConfigurationsForNode) {
            Set<String> callerConfigurations = getCallerConfigurationsByDependencyConfiguration(caller, ivyNode, dependencyConfiguration);
            Set<DefaultResolvedDependency> parentResolvedDependenciesForCallerConfigurations = getParentResolvedDependenciesByConfigurations(
                    parentResolvedDependencies,
                    callerConfigurations);
            for (DefaultResolvedDependency parentResolvedDependency : parentResolvedDependenciesForCallerConfigurations) {
                DefaultResolvedDependency resolvedDependency = resolvedDependencies.get(dependencyConfiguration);
                parentResolvedDependency.getChildren().add(resolvedDependency);
                resolvedDependency.getParents().add(parentResolvedDependency);
                resolvedDependency.addParentSpecificFiles(parentResolvedDependency,  getFilesForParent(parentResolvedDependency.getConfiguration(),
                        callerNode, caller, resolveReport));
            }
        }
    }

    private Set<File> getFilesForParent(String parentConfiguration, IvyNode callerNode, IvyNodeCallers.Caller caller, ResolveReport resolveReport) {
        Set<String> parentConfigurations = getConfigurationHierarchy(callerNode, parentConfiguration);
        Set<DependencyArtifactDescriptor> parentArtifacts = new LinkedHashSet<DependencyArtifactDescriptor>();
        for (String configuration : parentConfigurations) {
            parentArtifacts.addAll(WrapUtil.toSet(caller.getDependencyDescriptor().getDependencyArtifacts(configuration)));
        }
        ArtifactDownloadReport[] artifactDownloadReports = resolveReport.getArtifactsReports(caller.getDependencyDescriptor().getDependencyRevisionId());
        Set<File> files = new LinkedHashSet<File>();
        if (artifactDownloadReports != null) {
            for (ArtifactDownloadReport artifactDownloadReport : artifactDownloadReports) {
                for (DependencyArtifactDescriptor parentArtifact : parentArtifacts) {
                    if (isEquals(parentArtifact, artifactDownloadReport.getArtifact())) {
                        files.add(artifactDownloadReport.getLocalFile());    
                    }
                }
            }
        }
        return files;
    }

    private boolean isEquals(DependencyArtifactDescriptor parentArtifact, Artifact artifact) {
        return parentArtifact.getName().equals(artifact.getName())
                && parentArtifact.getExt().equals(artifact.getExt())
                && parentArtifact.getType().equals(artifact.getType())
                && parentArtifact.getQualifiedExtraAttributes().equals(artifact.getQualifiedExtraAttributes());
    }

    private boolean isRootCaller(ConfigurationResolveReport configurationResolveReport, IvyNodeCallers.Caller caller) {
        return caller.getModuleDescriptor().equals(configurationResolveReport.getModuleDescriptor());
    }

    private Set<DefaultResolvedDependency> getParentResolvedDependenciesByConfigurations(Map<String, DefaultResolvedDependency> parentResolvedDependencies,
                                                                                         Set<String> callerConfigurations) {
        Set<DefaultResolvedDependency> parentResolvedDependenciesSubSet = new LinkedHashSet<DefaultResolvedDependency>();
        for (String callerConfiguration : callerConfigurations) {
            for (DefaultResolvedDependency parentResolvedDependency : parentResolvedDependencies.values()) {
                if (parentResolvedDependency.containsConfiguration(callerConfiguration)) {
                    parentResolvedDependenciesSubSet.add(parentResolvedDependency);
                }
            }
        }
        return parentResolvedDependenciesSubSet;
    }

    private Set<String> getConfigurationHierarchy(IvyNode node, String configurationName) {
        Set<String> configurations = new HashSet<String>();
        configurations.add(configurationName);
        org.apache.ivy.core.module.descriptor.Configuration configuration = node.getConfiguration(configurationName);
        for (String extendedConfigurationNames : configuration.getExtends()) {
            configurations.addAll(getConfigurationHierarchy(node, extendedConfigurationNames));
        }
        return configurations;
    }

    private Set<String> getCallerConfigurationsByDependencyConfiguration(IvyNodeCallers.Caller caller, IvyNode dependencyNode, String dependencyConfiguration) {
        Map<String, Set<String>> dependency2CallerConfs = new LinkedHashMap<String, Set<String>>();
        for (String callerConf : caller.getCallerConfigurations()) {
            Set<String> dependencyConfs = getRealConfigurations(dependencyNode
                    , caller.getDependencyDescriptor().getDependencyConfigurations(callerConf));
            for (String dependencyConf : dependencyConfs) {
                if (!dependency2CallerConfs.containsKey(dependencyConf)) {
                    dependency2CallerConfs.put(dependencyConf, new LinkedHashSet<String>());
                }
                dependency2CallerConfs.get(dependencyConf).add(callerConf.toString());
            }
        }
        return dependency2CallerConfs.get(dependencyConfiguration);
    }

    private Set<String> getDependencyConfigurationsByCaller(IvyNode dependencyNode, IvyNodeCallers.Caller caller) {
        String[] dependencyConfigurations = caller.getDependencyDescriptor().getDependencyConfigurations(caller.getCallerConfigurations());
        Set<String> realDependencyConfigurations = getRealConfigurations(dependencyNode, dependencyConfigurations);
        return realDependencyConfigurations;
    }

    private Set<String> getRealConfigurations(IvyNode dependencyNode, String[] dependencyConfigurations) {
        Set<String> realDependencyConfigurations = new LinkedHashSet<String>();
        for (String dependencyConfiguration : dependencyConfigurations) {
            realDependencyConfigurations.addAll(WrapUtil.toSet(dependencyNode.getRealConfs(dependencyConfiguration)));
        }
        return realDependencyConfigurations;
    }

    private DefaultResolvedDependency createResolvedDependency(IvyNode ivyNode, ResolveReport resolveReport, String configuration) {
        ModuleRevisionId moduleRevisionId = ivyNode.getId();
        Set<String> configurations = getConfigurationHierarchy(ivyNode, configuration);
        DefaultResolvedDependency resolvedDependency = new DefaultResolvedDependency(
                moduleRevisionId.getOrganisation() + ":" +
                        moduleRevisionId.getName() + ":" +
                        moduleRevisionId.getRevision(),
                configuration,
                configurations,
                getFilesForReport(ivyNode, resolveReport.getArtifactsReports(ivyNode.getId()), configurations));
        return resolvedDependency;
    }

    private Set<File> getFilesForReport(IvyNode dependencyNode, ArtifactDownloadReport[] artifactDownloadReports, Set<String> configurations) {
        Set<ArtifactRevisionId> moduleArtifactsIdsForConfiguration = getModuleArtifactsIdsForConfiguration(dependencyNode, configurations);
        Set<File> files = new LinkedHashSet<File>();
        if (artifactDownloadReports != null) {
            for (ArtifactDownloadReport artifactDownloadReport : artifactDownloadReports) {
                if (moduleArtifactsIdsForConfiguration.contains(artifactDownloadReport.getArtifact().getId())) {
                    files.add(artifactDownloadReport.getLocalFile());
                }
            }
        }
        return files;
    }

    private Set<ArtifactRevisionId> getModuleArtifactsIdsForConfiguration(IvyNode dependencyNode, Set<String> configurations) {
        Set<ArtifactRevisionId> artifactRevisionIds = new HashSet<ArtifactRevisionId>();
        for (String hierarchyConfiguration : configurations) {
            Artifact[] artifactSubSet = dependencyNode.getDescriptor().getArtifacts(hierarchyConfiguration);
            for (Artifact artifact : artifactSubSet) {
                artifactRevisionIds.add(artifact.getId());
            }
        }
        return artifactRevisionIds;
    }

    private Map<ModuleRevisionId, Map<String, ModuleDependency>> createFirstLevelDependenciesModuleRevisionIds(Set<ModuleDependency> firstLevelDependencies) {
        Map<ModuleRevisionId, Map<String, ModuleDependency>> firstLevelDependenciesModuleRevisionIds =
                new LinkedHashMap<ModuleRevisionId, Map<String, ModuleDependency>>();
        for (ModuleDependency firstLevelDependency : firstLevelDependencies) {
            ModuleRevisionId moduleRevisionId = ModuleRevisionId.newInstance(
                    GUtil.elvis(firstLevelDependency.getGroup(), ""),
                    firstLevelDependency.getName(),
                    GUtil.elvis(firstLevelDependency.getVersion(), ""));
            if (firstLevelDependenciesModuleRevisionIds.get(moduleRevisionId) == null) {
                firstLevelDependenciesModuleRevisionIds.put(moduleRevisionId, new LinkedHashMap<String, ModuleDependency>());
            }
            firstLevelDependenciesModuleRevisionIds.get(moduleRevisionId).put(firstLevelDependency.getConfiguration(), firstLevelDependency);
        }
        return firstLevelDependenciesModuleRevisionIds;
    }

    private void addNodeIfFirstLevelDependency(IvyNode ivyNode, DefaultResolvedDependency resolvedDependency,
                                               Map<ModuleRevisionId, Map<String, ModuleDependency>> firstLevelDependencies2Nodes,
                                               Map<Dependency, Set<ResolvedDependency>> firstLevelResolvedDependencies) {
        ModuleRevisionId normalizedNodeId = normalize(ivyNode.getId());
        if (firstLevelDependencies2Nodes.containsKey(normalizedNodeId)) {
            ModuleDependency firstLevelNode = firstLevelDependencies2Nodes.get(normalizedNodeId).get(resolvedDependency.getConfiguration());
            if (firstLevelNode == null) {
                return;
            }
            if (!firstLevelResolvedDependencies.containsKey(firstLevelNode)) {
                firstLevelResolvedDependencies.put(firstLevelNode, new LinkedHashSet<ResolvedDependency>());
            }
            firstLevelResolvedDependencies.get(firstLevelNode).add(resolvedDependency);
        }
    }

    /*
    * Gradle has a different notion of equality then Ivy. We need to map the download reports to
    * moduleRevisionIds that are only use fields relevant for Gradle equality.
    */
    private ModuleRevisionId normalize(ModuleRevisionId moduleRevisionId) {
        return ModuleRevisionId.newInstance(
                moduleRevisionId.getOrganisation(),
                moduleRevisionId.getName(),
                moduleRevisionId.getRevision());
    }
}
