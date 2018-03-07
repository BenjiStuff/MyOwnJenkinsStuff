package com.gxsoftware.jenkins;

/**
 * Contains all utility methods. These are simple method which hide some basic technical aspects.
 */
class Utilities implements Serializable {

    def steps
    def env
    def currentBuild

    /**
     * Before being able to use the utilities, the steps need to be passed into the utilities class
     * <pre>
     *     @Library('gx-shared-lib')
     *     import com.gxsoftware.jenkins.Utilities
     *     def utils = new Utilities(steps)
     * </pre>
     * @param steps
     */
    Utilities(steps, env, currentBuild) {
        this.steps = steps
        this.env = env
        this.currentBuild = currentBuild
    }

    /**
     * Execute a maven command
     * @param args The arguments to pass to maven
     */
    def mvn(args) {
        //steps.sh "export JAVA_HOME=/usr/local/java-latest  && ${steps.tool 'M3'}/bin/mvn -s /vol/jenkins/config/settings.xml ${args}"
        steps.bat "${steps.tool 'M3'}/bin/mvn -s ${args}"
    }

    /**
     * Execute the sonar QA. This is done in a seperate method since the maven arguments for this are always the same.
     */
    def sonar() {
        mvn "verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dsonar.host.url=http://localhost:9000/sonar"
    }

    /**
     * Clones a git repository.
     * @param gitRepo The reopsitory (minus the .git) to clone from git
     */
    def fromGit(gitRepo) {
        //steps.git url: "https://git.gxsoftware.com/git/${gitRepo}.git", credentialsId: 'git'
    }

    def commitGit(msg) {
        mvn "scm:checkin -Dmessage='${msg}' -DpushChanges=false"
    }

    def tagGit(gitRepo, tag, msg) {
        steps.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'git', passwordVariable: 'pwd', usernameVariable: 'un']]) {
            //mvn "scm:tag -Dusername=${env.un} -Dpassword=${env.pwd} -Dtag=${tag} -Dmessage='${msg}' -DpushChanges=true -DdeveloperConnectionUrl=scm:git:https://git.gxsoftware.com/git/${gitRepo}.git"
        }
    }

    def pushGit(gitRepo, msg) {
        steps.withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'git', passwordVariable: 'pwd', usernameVariable: 'un']]) {
            //mvn "scm:checkin -Dusername=${env.un} -Dpassword=${env.pwd} -Dmessage='${msg}' -DpushChanges=true -DdeveloperConnectionUrl=scm:git:https://git.gxsoftware.com/git/${gitRepo}.git"
        }
    }

    def mergeFromTo(gitRepo, branchFrom, branchTo, msg) {
        //steps.git url: "https://git.gxsoftware.com/git/${gitRepo}.git", credentialsId: 'git', branch: branchTo;
        //steps.sh "git merge ${branchFrom}";
        //pushGit(gitRepo, msg);
        //steps.git url: "https://git.gxsoftware.com/git/${gitRepo}.git", credentialsId: 'git', branch: branchFrom;
    }

    /**
     * Send an email to the supplied recipients
     * @param subject The subject of the email
     * @param body The body of the email
     * @param recipients The list of recipients
     */
    def mailTo(subject, body, recipients) {
        //steps.emailext replyTo: "no.reply@gxsoftware.com", body: "${body}", to: "${recipients}", subject: "${subject}"
    }
}