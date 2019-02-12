pipeline {
   agent any
   stages {

      stage ('Build') {
         when {
            branch 'master'
         }
         steps {
            sh "rm -rf build/"
            sh "chmod +x gradlew"
            sh "./gradlew build uploadArchives --refresh-dependencies --stacktrace"
         }
      }
   }
}