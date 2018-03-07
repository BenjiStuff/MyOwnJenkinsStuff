package com.mycompany.app;

import org.apache.maven.model.Model;

/**
 * Contains all release methods. With these it is possible to create a new release, verify it, upload it to Nexus and tag it in GIT.
 */
class Release implements Serializable {

    def steps
    def env
    def currentBuild

    def utils

    /**
     * Before being able to use the release class, the steps need to be passed into the release class
     * <pre>
     * @Library( ' gx-shared-lib')
     * import com.gxsoftware.jenkins.Release
     *     def release = new Release(steps)
     * </pre>
     * @param steps
     */
    Release(steps, env, currentBuild) {
        this.steps = steps
        this.env = env
        this.currentBuild = currentBuild

        this.utils = new Utilities(steps, env, currentBuild)
    }

    Model read_pom(pom_file) {
        steps.echo pom_file
        return steps.readMavenPom(file: pom_file)
    }

    String get_version_from_pom(pom_file, strip_snap_shot = true) {
        def pom = read_pom pom_file
        String version = pom.version
        String releaseNumber;
        if (strip_snap_shot && version.endsWith("-SNAPSHOT")) {
            releaseNumber = version.substring(0, version.length() - 9)
        } else {
            releaseNumber = version
        }
        return releaseNumber
    }

    String create_release_tag(pom_file, release_number) {
        def pom = read_pom pom_file
        return pom.artifactId + '-' + release_number;
    }

    def updatePomWithVersion(releaseNumber) {
        utils.mvn "versions:set -DnewVersion=${releaseNumber}"
    }

    def createRelease() {
        createRelease("");
    }

    def createRelease(String profile) {
        String profileArg = "";
        if (profile.trim()) {
            profileArg = "-P${profile}";
        }
        utils.mvn "${profileArg} install"
    }

    String determineNextDeveloperVersion(String releaseNumber) {
        def releaseArray = releaseNumber.split(/\./)
        def major = releaseArray[0]
        def minor = releaseArray[1]
        def micro = releaseArray[2]
        int microInt = new Integer(micro)
        microInt = microInt + 1
        return major + '.' + minor + '.' + Integer.toString(microInt) + '-SNAPSHOT'
    }

    def verifyChangeLog(String releaseNumber) {
        steps.step([$class: 'ChangelogVerifierBuilder', version: "${releaseNumber}", fileName: 'target/classes/changelog.txt'])
        if(currentBuild.result == 'FAILURE') {
            steps.error 'Unable to proceed, automated Changelog verification failed'
        }
    }

    def verifyPom(String releaseNumber) {
        verifyPom(releaseNumber, "wms-maven-parent-wcbs")
    }

    def verifyPom(String releaseNumber, String projectParent) {
        utils.mvn "help:effective-pom -Doutput=target/effectivePom.xml"

        steps.echo '==== effective POM ===='
        steps.echo steps.readFile('target/effectivePom.xml')
        steps.step([$class: 'PomVerifierBuilder', version: "${releaseNumber}", fileName: 'target/effectivePom.xml', projectParent: "${projectParent}"])
        if(currentBuild.result == 'FAILURE') {
            steps.error 'Unable to proceed, automated POM verification failed'
        }
    }

    def verifyManifest(String releaseNumber) {
        steps.step([$class: 'ManifestVerifierBuilder', version: "${releaseNumber}"])
        steps.echo "verifyManifest result: ${currentBuild.result}"
        if(currentBuild.result == 'FAILURE') {
            steps.error 'Unable to proceed, automated MANIFEST.MF verification failed'
        }
    }

    def deploy(releaseNumber, repository) {
        steps.echo "deploying version ${releaseNumber} to repository ${repository}"
        deploy(releaseNumber, repository, '', '');
    }

    def deploy(releaseNumber, repository, profile) {
        steps.echo "deploying version ${releaseNumber} to repository ${repository} with profile ${profile}"
        deploy(releaseNumber, repository, profile, '');
    }

    def deploy(releaseNumber, repository, profile, folder) {
        // determine package type
        def pom = read_pom "${folder}pom.xml"
        def modules = pom.modules
        def groupId = pom.groupId
        def packaging = pom.packaging
        def artifactId = pom.artifactId
        if (groupId == null || groupId.empty) {
            groupId = pom.parent.groupId
        }
        if (packaging == null || packaging.empty) {
            packaging = "jar"
        }
        steps.echo "packaging ${groupId}:${artifactId}:${releaseNumber}:${packaging}"

        // deploy based on package type
        switch(packaging) {
            case 'bundle':
            case 'jar' :
                deployJar(repository, groupId, artifactId, releaseNumber, packaging, profile, folder)
                break
            case 'pom' :
                deployPom(repository, groupId, artifactId, releaseNumber, packaging, modules, profile, folder)
                break
            default :
                break
        }
    }

    def createProjectDetailPage(pomFile, gitRepo, releaseNumber) {
        createProjectDetailPage(pomFile, gitRepo, releaseNumber, "", false)
    }

    def createProjectDetailPage(pomFile, gitRepo, releaseNumber, String profile, boolean includeProjectOverview) {
        def pomString = steps.readFile(pomFile);
        steps.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'git', passwordVariable: 'pwd', usernameVariable: 'un']]) {
            if (includeProjectOverview) {
                steps.step([$class: 'ProjectOverview', gitRepo: "${gitRepo}", userName: "${env.un}", password: "${env.pwd}", fileName: "projectOverview.log", pom: "${pomString}", releaseNumber: "${releaseNumber}"])
            }
            steps.step([$class: 'DownloadsOverview', gitRepo: "${gitRepo}", userName: "${env.un}", password: "${env.pwd}", fileName: "DownloadsOverview.log", pom: "${pomString}", releaseNumber: "${releaseNumber}"])
        }
        String profileArg = "";
        if (profile.trim()) {
            profileArg = "-P${profile}";
        }
        utils.mvn "${profileArg} -DusedBy=projectOverview.log -DskipTests install confluence-reporting:deploy"
    }

    def deployJar(repository, groupId, artifactId, releaseNumber, packaging, profile, folder) {
        // when deploying a jar, we need to check if there is also a sources jar file in the target folder
        steps.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'nexus', passwordVariable: 'pwd', usernameVariable: 'un']]) {
            steps.sh "curl -v -F r=${repository} -F hasPom=true -F e=jar -F file=@${folder}pom.xml -F file=@${folder}target/${artifactId}-${releaseNumber}.jar -u ${env.un}:${env.pwd} http://nexus.product.gx.local/nexus/service/local/artifact/maven/content > ${folder}target/deploy.log"
        }
        verifyDeploy(folder)

        if (steps.fileExists("${folder}target/${artifactId}-${releaseNumber}-sources.jar")) {
            steps.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'nexus', passwordVariable: 'pwd', usernameVariable: 'un']]) {
                steps.sh "curl -v -F r=${repository} -F g=${groupId} -F a=${artifactId} -F v=${releaseNumber} -F p=${packaging} -F c=sources -F e=jar -F file=@${folder}target/${artifactId}-${releaseNumber}-sources.jar -u ${env.un}:${env.pwd} http://nexus.product.gx.local/nexus/service/local/artifact/maven/content > ${folder}target/deploy.log"
            }
            verifyDeploy(folder)
        }
    }

    def deployPom(repository, groupId, artifactId, releaseNumber, packaging, modules, profile, folder) {
        // when deploying a pom, we need to check if there is also a zip file in the target folder
        if (steps.fileExists("${folder}target/${artifactId}-${releaseNumber}-jars.zip")) {
            steps.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'nexus', passwordVariable: 'pwd', usernameVariable: 'un']]) {
                steps.sh "curl -v -F r=${repository} -F hasPom=true -F e=zip -F c=jars -F file=@${folder}pom.xml -F file=@${folder}target/${artifactId}-${releaseNumber}-jars.zip -u ${env.un}:${env.pwd} http://nexus.product.gx.local/nexus/service/local/artifact/maven/content > ${folder}target/deploy.log"
            }
            verifyDeploy(folder)
            if (steps.fileExists("${folder}target/${artifactId}-${releaseNumber}-sources.zip")) {
                steps.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'nexus', passwordVariable: 'pwd', usernameVariable: 'un']]) {
                    steps.sh "curl -v -F r=${repository} -F g=${groupId} -F a=${artifactId} -F v=${releaseNumber} -F p=${packaging} -F c=sources -F e=zip -F file=@${folder}target/${artifactId}-${releaseNumber}-sources.zip -u ${env.un}:${env.pwd} http://nexus.product.gx.local/nexus/service/local/artifact/maven/content > ${folder}target/deploy.log"
                }
                verifyDeploy(folder)
            }
        } else {
            steps.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'nexus', passwordVariable: 'pwd', usernameVariable: 'un']]) {
                steps.sh "curl -v -F r=${repository} -F hasPom=true -F file=@${folder}pom.xml -u ${env.un}:${env.pwd} http://nexus.product.gx.local/nexus/service/local/artifact/maven/content > target/deploy.log"
            }
            verifyDeploy(folder)
        }

        for (module in modules) {
            steps.echo "processing module ${module}"
            deploy(releaseNumber, repository, profile, "${folder}${module}/")
        }
    }

    def verifyDeploy(folder) {
        def deployLog = steps.readFile "${folder}target/deploy.log"
        steps.echo '========== deloy.log =========='
        steps.echo deployLog
        def deployLines = deployLog.readLines()
        for (line in deployLines) {
            if (line =~ /<html>/) {
                def html = new XmlSlurper().parseText(deployLog)
                def errorMsg = html.body.error.text()
                html = null
                sendMailToOwner("deploy failed : ${errorMsg}", "See the ${env.BUILD_URL} console")
                steps.error "deploy failed : ${errorMsg}"
            }
        }
    }

    def sendMailToOwner(subjectValue, bodyValue) {
        def pom = read_pom "pom.xml"
        for (developer in pom.developers) {
            if (developer.roles.contains("owner")) {
                utils.mailTo("${env.BUILD_TAG} - ${subjectValue}", bodyValue, developer.email)
            }
        }
    }
}