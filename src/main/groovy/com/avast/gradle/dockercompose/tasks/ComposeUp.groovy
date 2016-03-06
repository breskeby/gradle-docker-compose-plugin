package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
import com.avast.gradle.dockercompose.ServiceHost
import com.avast.gradle.dockercompose.ServiceHostType
import com.avast.gradle.dockercompose.ServiceInfo
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec
import org.yaml.snakeyaml.Yaml

class ComposeUp extends DefaultTask {

    private Map<String, ServiceInfo> servicesInfos = new HashMap<>()
    ComposeExtension extension

    Map<String, ServiceInfo> getServicesInfos() {
        servicesInfos
    }

    ComposeUp() {
        group = 'docker'
        description = 'Builds and starts all containers of docker-compose project'
    }

    @TaskAction
    void up() {
        if (extension.buildBeforeUp) {
            project.exec { ExecSpec e ->
                e.commandLine 'docker-compose', 'build'
            }
        }
        project.exec { ExecSpec e ->
            e.commandLine 'docker-compose', 'up', '-d'
        }
        servicesInfos = loadServicesInfo().collectEntries { [(it.name): (it)] }
        if (extension.waitForTcpPorts) {
            waitForOpenTcpPorts(servicesInfos.values())
        }
    }

    protected Iterable<ServiceInfo> loadServicesInfo() {
        def compose = (Map<String, Object>)(new Yaml().load(project.file('docker-compose.yml').text))
        // if there is 'version: 2' on top-level then information about services is in 'services' sub-tree
        Iterable<String> servicesNames = '2'.equals(compose.get('version')) ? ((Map)compose.get('services')).keySet() : compose.keySet()
        servicesNames.collect { createServiceInfo(it) }
    }

    protected ServiceInfo createServiceInfo(String serviceName) {
        String containerId = getContainerId(serviceName)
        logger.info("Container ID of $serviceName is $containerId")
        def inspection = getDockerInspection(containerId)
        ServiceHost host = getServiceHost(serviceName, inspection)
        logger.info("Will use $host as host of $serviceName")
        def tcpPorts = getTcpPortsMapping(serviceName, inspection, host)
        new ServiceInfo(name: serviceName, serviceHost: host, tcpPorts: tcpPorts, containerHostname: inspection.Config.Hostname, inspection: inspection)
    }

    String getContainerId(String serviceName) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.commandLine 'docker-compose', 'ps', '-q', serviceName
                e.standardOutput = os
            }
            os.toString().trim()
        }
    }

    Map<String, Object> getDockerInspection(String containerId) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.commandLine 'docker', 'inspect', containerId
                e.standardOutput os
            }
            def inspectionAsString = os.toString()
            logger.debug("Inspection for container $containerId: $inspectionAsString")
            (new Yaml().load(inspectionAsString))[0] as Map<String, Object>
        }
    }

    ServiceHost getServiceHost(String serviceName, Map<String, Object> inspection) {
        String dockerHost = System.getenv('DOCKER_HOST')
        if (dockerHost) {
            logger.debug("'DOCKER_HOST environment variable detected - will be used as hostname of $serviceName'")
            new ServiceHost(host: dockerHost.toURI().host, type: ServiceHostType.Remote)
        } else {
            // read IP address of container from inspection
            // ServiceHostType influences port mapping
            Map<String, Object> networkSettings = inspection.NetworkSettings
            String ipAddress = networkSettings.IPAddress
            if (ipAddress) {
                logger.debug("Will use $ipAddress as host of $serviceName")
                new ServiceHost(host: ipAddress, type: ServiceHostType.Bridge)
            } else {
                // read IPAddress of first network
                Map<String, Object> networks = networkSettings.Networks
                Map<String, Object> firstNetwork = networks.values().head()
                ipAddress = firstNetwork.Gateway
                logger.debug("Will use $ipAddress (network ${networks.keySet().head()}) as host of $serviceName")
                new ServiceHost(host: ipAddress, type: ServiceHostType.CustomNetwork)
            }
        }
    }

    Map<Integer, Integer> getTcpPortsMapping(String serviceName, Map<String, Object> inspection, ServiceHost host) {
        Map<Integer, Integer> ports = [:]
        inspection.NetworkSettings.Ports.each { String exposedPortWithProtocol, forwardedPortsInfos ->
            def (String exposedPortAsString, String protocol) = exposedPortWithProtocol.split('/')
            if (!"tcp".equalsIgnoreCase(protocol)) {
                return // from closure
            }
            int exposedPort = exposedPortAsString as int
            if (!forwardedPortsInfos || forwardedPortsInfos.isEmpty()) {
                logger.debug("No forwarded TCP port for $serviceName:$exposedPort")
            }
            else {
                switch (host.type) {
                    case ServiceHostType.Bridge:
                        ports.put(exposedPort, exposedPort)
                        logger.info("Exposed TCP port $serviceName:$exposedPort will be available as the same port because we connect to the container directly")
                        break
                    case ServiceHostType.CustomNetwork:
                    case ServiceHostType.Remote:
                        if (forwardedPortsInfos.size() > 1) {
                            logger.warn("More forwarded TCP ports for $serviceName:$exposedPort $forwardedPortsInfos Will use the first one.")
                        }
                        def forwardedPortInfo = forwardedPortsInfos.first()
                        int forwardedPort = forwardedPortInfo.HostPort as int
                        logger.info("Exposed TCP port $serviceName:$exposedPort will be available as $forwardedPort")
                        ports.put(exposedPort, forwardedPort)
                        break
                    default:
                        throw new IllegalArgumentException("Unknown ServiceHostType '${host.type}' for service $serviceName")
                        break
                }
            }
        }
        ports
    }

    void waitForOpenTcpPorts(Iterable<ServiceInfo> servicesInfos) {
        servicesInfos.forEach { service ->
            service.tcpPorts.forEach { exposedPort, forwardedPort ->
                logger.lifecycle("Probing TCP socket on ${service.host}:${forwardedPort} of ${service.name}")
                while (true) {
                    try {
                        def s = new Socket(service.host, forwardedPort)
                        s.close()
                        logger.lifecycle("TCP socket on ${service.host}:${forwardedPort} of ${service.name} is ready")
                        return
                    }
                    catch (Exception e) {
                        logger.lifecycle("Waiting for TCP socket on ${service.host}:${forwardedPort} of ${service.name} (${e.message})")
                        sleep(extension.waitAfterTcpProbeFailure.toMillis())
                    }
                }
            }
        }
    }
}
