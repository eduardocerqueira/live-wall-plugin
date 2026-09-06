# A throwaway Jenkins with Live Wall already installed. Built by scripts/demo.sh; the build
# context it needs is assembled there, not checked in.
#
# Anything under /usr/share/jenkins/ref is copied into JENKINS_HOME the first time the container
# starts, which is the supported way to preinstall a plugin and a startup hook. It means one boot
# rather than "start, copy, fix ownership, restart".

ARG JENKINS_IMAGE=jenkins/jenkins:lts-jdk21
FROM ${JENKINS_IMAGE}

COPY --chown=jenkins:jenkins live-wall.hpi /usr/share/jenkins/ref/plugins/live-wall.jpi
COPY --chown=jenkins:jenkins init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/

# No setup wizard and no login: this controller is a scratch pad on your laptop, and the script
# that drives it needs to create a couple of hundred jobs without a token dance.
#
# It follows that you must not publish the port. See docs/demo.md.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
