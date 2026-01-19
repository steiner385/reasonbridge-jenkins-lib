#!/usr/bin/env groovy
/**
 * Standard CI Pipeline for uniteDiscord
 *
 * This is the main entry point that encapsulates the entire CI workflow.
 * The Jenkinsfile in the application repo should be a thin loader that just calls:
 *   standardPipeline()
 *
 * Features:
 * - Generic Webhook Trigger for branch-specific builds
 * - Auto-detect package manager (pnpm, yarn, npm)
 * - GitHub status reporting
 * - Composable stages
 *
 * Configuration options:
 *   standardPipeline(
 *     githubOwner: 'steiner385',      // GitHub org/user
 *     githubRepo: 'uniteDiscord',     // Repository name
 *     webhookToken: 'uniteDiscord-ci', // Webhook token
 *     statusContext: 'Jenkins CI',    // GitHub status context
 *     buildPackages: true,            // Build monorepo packages
 *     runLint: true,                  // Run linting
 *     runUnitTests: true,             // Run unit tests
 *     runBuild: true,                 // Run final build
 *     timeoutMinutes: 60              // Pipeline timeout
 *   )
 */

def call(Map config = [:]) {
    // Default configuration
    def cfg = [
        githubOwner: config.githubOwner ?: 'steiner385',
        githubRepo: config.githubRepo ?: 'uniteDiscord',
        webhookToken: config.webhookToken ?: "${config.githubRepo ?: 'uniteDiscord'}-ci",
        statusContext: config.statusContext ?: 'Jenkins CI',
        buildPackages: config.buildPackages != false,
        runLint: config.runLint != false,
        runUnitTests: config.runUnitTests != false,
        runBuild: config.runBuild != false,
        timeoutMinutes: config.timeoutMinutes ?: 60
    ]

    pipeline {
        agent any

        options {
            timestamps()
            buildDiscarder(logRotator(numToKeepStr: '30'))
            timeout(time: cfg.timeoutMinutes, unit: 'MINUTES')
            disableConcurrentBuilds(abortPrevious: true)
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ref', value: '$.ref'],
                    [key: 'after', value: '$.after'],
                    [key: 'repository_name', value: '$.repository.name'],
                    [key: 'pusher_name', value: '$.pusher.name', defaultValue: 'unknown']
                ],
                causeString: "Triggered by push to \$ref by \$pusher_name",
                token: cfg.webhookToken,
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
                regexpFilterText: '$repository_name',
                regexpFilterExpression: "^${cfg.githubRepo}\$"
            )
        }

        environment {
            GITHUB_OWNER = cfg.githubOwner
            GITHUB_REPO = cfg.githubRepo
            CI = 'true'
            NODE_ENV = 'test'
            PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            BRANCH_NAME = "${params.ref ? params.ref.replaceAll('refs/heads/', '') : 'main'}"
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        initializeCheckout(cfg)
                    }
                    githubStatusReporter(
                        status: 'pending',
                        context: cfg.statusContext,
                        description: 'Build in progress'
                    )
                    sh 'rm -rf frontend/frontend || true'
                }
            }

            stage('Install Dependencies') {
                steps {
                    installDependencies()
                }
            }

            stage('Build Packages') {
                when {
                    expression { return cfg.buildPackages }
                }
                steps {
                    script {
                        def pm = pipelineHelpers.getPackageManager()
                        sh "${pm.filter} \"./packages/*\" -r run build"
                    }
                }
            }

            stage('Lint') {
                when {
                    expression { return cfg.runLint }
                }
                steps {
                    script {
                        def pm = pipelineHelpers.getPackageManager()
                        sh "${pm.run} lint"
                    }
                }
            }

            stage('Unit Tests') {
                when {
                    expression { return cfg.runUnitTests }
                }
                steps {
                    script {
                        def pm = pipelineHelpers.getPackageManager()
                        try {
                            withAwsCredentials {
                                sh "${pm.run} test:unit -- --coverage"
                            }
                        } catch (Exception e) {
                            echo "WARNING: AWS credentials not available: ${e.message}"
                            sh "${pm.run} test:unit -- --coverage"
                        }
                    }
                }
                post {
                    always {
                        junit allowEmptyResults: true, testResults: 'coverage/junit.xml'
                    }
                }
            }

            stage('Build') {
                when {
                    expression { return cfg.runBuild }
                }
                steps {
                    script {
                        def pm = pipelineHelpers.getPackageManager()
                        sh "${pm.run} build"
                    }
                }
            }
        }

        post {
            success {
                echo 'Build succeeded!'
                githubStatusReporter(
                    status: 'success',
                    context: cfg.statusContext,
                    description: 'All checks passed'
                )
            }
            failure {
                echo 'Build failed!'
                githubStatusReporter(
                    status: 'failure',
                    context: cfg.statusContext,
                    description: 'Build or tests failed'
                )
            }
        }
    }
}

/**
 * Initialize checkout based on webhook payload
 */
def initializeCheckout(Map cfg) {
    def checkoutBranch = env.BRANCH_NAME ?: 'main'
    def checkoutCommit = params.after ?: ''

    echo "Building branch: ${checkoutBranch}"
    if (checkoutCommit) {
        echo "Commit SHA: ${checkoutCommit}"
    }

    checkout([
        $class: 'GitSCM',
        branches: [[name: checkoutCommit ?: "*/${checkoutBranch}"]],
        userRemoteConfigs: [[
            url: "https://github.com/${cfg.githubOwner}/${cfg.githubRepo}.git",
            credentialsId: 'github-credentials'
        ]]
    ])

    env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
    echo "Building commit: ${env.GIT_COMMIT_SHORT}"
}

return this
