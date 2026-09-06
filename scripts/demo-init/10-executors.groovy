/*
 * Runs once, the first time the demo controller starts.
 *
 * Two default executors means seeding two hundred sample jobs takes minutes of queueing rather
 * than seconds of building. Nothing here is a recommendation for a real controller.
 */
import jenkins.model.Jenkins

def jenkins = Jenkins.get()
jenkins.setNumExecutors(10)
jenkins.setSystemMessage('Live Wall demo controller. Throwaway data, no security. Do not expose this port.')
jenkins.save()
