import org.codehaus.groovy.grails.commons.GrailsClassUtils as GCU
import grails.util.GrailsNameUtils as GNU
import org.codehaus.groovy.grails.plugins.support.GrailsPluginUtils
import grails.util.GrailsUtil

import org.perf4j.log4j.Log4JStopWatch

import org.grails.plugins.perf4j.ProfiledOptionsBuilder
import org.grails.plugins.perf4j.ControllerProfiledOptionsCache


class Perf4jGrailsPlugin {
    // the plugin version
    def version = "0.1.1"
    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "1.1 > *"
    // the other plugins this plugin depends on
    def dependsOn = [:]
    def loadAfter = ['core', 'hibernate', 'services', 'controllers', 'logging', 'quartz']
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
            "grails-app/views/error.gsp", "web-app/**"
    ]
    def observe = [ 'controllers', 'services', 'hibernate', 'quartz' ]
    

    // plugin metadata
    def name = "perf4j"
    def author = "Daniel Rinser"
    def authorEmail = "grails@danielrinser.de"
    def title = "Perf4J Integration Plugin"
    def description = '''This plugin integrates the Perf4J performance statistics library (http://perf4j.codehaus.org) into Grails applications. It provides idiomatic 
ways to profile individual code blocks and automatic, customizable profiling of service methods.'''

    // URL to the plugin's documentation
    def documentation = "http://www.grails.org/plugin/perf4j"


    // the name of the config property in services
    static final String PROFILED_PROPERTY = "profiled"
    // indicates whether profiling is enabled AT ALL (according to config option or implicitly by environment)
    static Boolean profilingEnabled = false
    // indicates whether profiling is CURRENTLY enabled (as set via "profilingEnabled" property during runtime)
    static Boolean profilingCurrentlyEnabled = true

    private static dummyObjectInstance = new DummyObject()


    def doWithSpring = {
        // register controller options cache as spring bean
        controllerProfiledOptionsCache(ControllerProfiledOptionsCache)
    }

    def doWithApplicationContext = { applicationContext ->
    }

    def doWithWebDescriptor = { xml ->
        // get config values (or use reasonable default values)
        def graphingServletEnabled = (application.config.flatten().containsKey("perf4j.graphingServlet.enabled") ? application.config.perf4j.graphingServlet.enabled : GrailsUtil.isDevelopmentEnv())
        def graphNames = (application.config.flatten().containsKey("perf4j.graphingServlet.graphNames") ? application.config.perf4j.graphingServlet.graphNames : "performanceGraph")
        def urlPattern = (application.config.flatten().containsKey("perf4j.graphingServlet.urlPattern") ? application.config.perf4j.graphingServlet.urlPattern : "/perf4j")
        
        // register perf4j graphing servlet
        if(graphingServletEnabled) {
            log.info "Registering Perf4J graphing servlet..."
            log.debug "URL pattern: ${urlPattern}"
            log.debug "Graph names: ${graphNames}"

            def servlets = xml.'servlet'
            servlets[servlets.size() - 1] + {
                servlet {
                    'servlet-name'("perf4jServlet")
                    'display-name'("Perf4J Graphing Servlet")
                    'servlet-class'("org.perf4j.log4j.servlet.GraphingServlet")
                    'init-param' {
                        'param-name'("graphNames")
                        'param-value'(graphNames)
                    }
                }
            }
        
            def servletMappings = xml.'servlet-mapping'
            servletMappings[servletMappings.size() - 1] + {
                'servlet-mapping' {
                    'servlet-name'("perf4jServlet")
                    'url-pattern'(urlPattern)
                }
            }
        }
    }

    def doWithDynamicMethods = { ctx ->
        evaluateConfigSettings(application, log)
        addPerf4jFeaturesToAllArtefacts(application, log)
    }

    def onChange = { event ->
        if(application.isControllerClass(event.source)) {
            addPerf4jMethods(event.source, log)

            // invalidate the options cache for this controller (they may have changed)
            def logicalName = GNU.getLogicalName(event.source, "Controller")
            if(logicalName) {
                logicalName = logicalName[0].toLowerCase() + (logicalName.size() > 1 ? logicalName[1..-1] : '')

                def cache = event.ctx.controllerProfiledOptionsCache
                cache.invalidateCacheForController(logicalName)
            }
        }
        else if(application.isDomainClass(event.source)) {
            addPerf4jMethods(event.source, log)
        }
        else if(application.isServiceClass(event.source)) {
            addPerf4jMethods(event.source, log)
            addPerf4jGlobalProfiling(event.source, log)
        }
        else if(application.artefactHandlers.any { it.type == 'Task' } && application.isTaskClass(event.source)) {
            addPerf4jMethods(event.source, log)
        }
    }

    def onConfigChange = { event ->
        def oldEnabled = profilingEnabled
        evaluateConfigSettings(application, log)
        
        if(oldEnabled != profilingEnabled) {
            // re-register all methods
            addPerf4jFeaturesToAllArtefacts(application, log)
        }
    }


    /**
     *
     */
    def evaluateConfigSettings(application, log) {
        log.info "Evaluating new Perf4j config settings..."
        profilingEnabled = (application.config.flatten().containsKey("perf4j.enabled") ? application.config.perf4j.enabled : GrailsUtil.isDevelopmentEnv())
    }

    def addPerf4jFeaturesToAllArtefacts(application, log) {
        application.controllerClasses.each() {
            addPerf4jMethods(it.clazz, log)
        }

        application.domainClasses.each() {
            addPerf4jMethods(it.clazz, log)
        }

        application.serviceClasses.each() {
            addPerf4jMethods(it.clazz, log)
            addPerf4jGlobalProfiling(it.clazz, log)
        }
        
        if(application.artefactHandlers.any { it.type == 'Task' }) {
            application.taskClasses.each {
                addPerf4jMethods(it.clazz, log)
            }
        }
    }
    
    /**
     * register profiling methods
     */
    def addPerf4jMethods(artefactClass, log) {
        log.info "Adding Per4j methods: ${artefactClass}..."


        artefactClass.metaClass.withStopwatch << { Closure callable ->
            withStopwatch("", null, callable)
        }

        artefactClass.metaClass.withStopwatch << { String tag, Closure callable ->
            withStopwatch(tag, null, callable)
        }

        artefactClass.metaClass.withStopwatch << { String tag, String message, Closure callable ->
            if(this.profilingEnabled && this.profilingCurrentlyEnabled) {
                def stopWatch = new Log4JStopWatch()
                def retVal
                try {
                    retVal = callable.call(stopWatch)
                }
                finally {
                    if(message != null)
                        stopWatch.stop(tag, message)
                    else
                        stopWatch.stop(tag)
                }
                return retVal
            }
            
            // profiling disabled
            else {
                // call the closure with our dummy object to prevent NPEs
                return callable.call(dummyObjectInstance)
            }
        }
        
        artefactClass.metaClass.setProfilingEnabled << { Boolean enabled ->
            this.profilingCurrentlyEnabled = enabled
        }

        artefactClass.metaClass.getProfilingEnabled << { ->
            this.profilingCurrentlyEnabled
        }
    }
    
    /**
     * register global profiling
     */
    def addPerf4jGlobalProfiling(artefactClass, log) {
        /*
         *  Only register interception code if profiling is enabled.
         *  Note: In development environment, the interception code is always added (with runtime check if profiling is enabled),
         *  because otherwise we would not be able to "hot enable" it upon config change (adding methods via ExpandoMetaClass is only
         *  allowed within doWithDynamicMethods()).
         */
        if(profilingEnabled || GrailsUtil.isDevelopmentEnv()) {
            log.info "Adding Per4j interception code: ${artefactClass}..."

            def profiled = GCU.getStaticPropertyValue(artefactClass, PROFILED_PROPERTY)
            def profilingOptions = null

            if(profiled instanceof Boolean && profiled) {
                log.debug "Found static profiled property of type Boolean..."

                profilingOptions = [:]
                getInterceptableMethods(artefactClass)*.name.each { methodName ->
                    profilingOptions[methodName] = [tag: "${artefactClass.name}.${methodName}"]
                }
            }
            
            else if(profiled instanceof List && profiled.size() > 0) {
                log.debug "Found static profiled property of type List..."

                profilingOptions = [:]
                profiled.each { methodName ->
                    profilingOptions[methodName] = [tag: "${artefactClass.name}.${methodName}"]
                }
            }
            
            else if(profiled instanceof Closure) {
                log.debug "Found static profiled property of type Closure..."

                // run closure with builder as delegate
                def builder = new ProfiledOptionsBuilder()
                profiled.delegate = builder
                profiled.resolveStrategy = Closure.DELEGATE_ONLY
                profiled.call()
                profilingOptions = builder.profiledMap

                // set default tags for all methods without explicitly defined tag
                profilingOptions.each { methodName, options ->
                    if(options.tag == null) {
                        options.tag = "${artefactClass.name}.${methodName}"
                    }
                }
            }
            
            
            if(profilingOptions) {
                // TODO: check method names in options against actual methods in service (-> getInterceptableMethods)
                // and log warning if options include non-existing methods

                log.debug "Profiling options for ${artefactClass}: ${profilingOptions}"
                
                artefactClass.metaClass.invokeMethod = { String name, args ->
                    def metaMethod = artefactClass.metaClass.getMetaMethod(name, args)
                    if(metaMethod) {
                        if(this.profilingEnabled && this.profilingCurrentlyEnabled && profilingOptions.containsKey(name)) {
                            def tag = profilingOptions[name]?.tag ?: ""
                            def message = profilingOptions[name]?.message ?: null
                            def stopWatch = new Log4JStopWatch(tag, message)
                            def retVal
                            try {
                                retVal = metaMethod.invoke(delegate, args)
                            }
                            finally {
                                stopWatch.stop()
                            }
                            return retVal
                        }
                        else {
                            return metaMethod.invoke(delegate, args)
                        }
                    }
                    else {
                        return artefactClass.metaClass.invokeMissingMethod(delegate, name, args)
                    }
                }
            }
        }
        else {
            log.info "NOT adding Per4j interception code: ${artefactClass}... Perf4j is disabled in config and we are not in development environment."
        }
    }
    
    /**
     *  Get all non-synthetic methods of a (service) class, ie. all methods that are defined in the source code.
     */
    def getInterceptableMethods(artefactClass) {
        artefactClass.declaredMethods.grep {
            !it.synthetic && !(it.name ==~ /(get|set)Profiled/)
        }
    }
}

/**
 *  This is just a dummy class, whose instances accept all methods.
 */
class DummyObject {
    def methodMissing(String name, args) { }
}
