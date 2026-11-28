pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    triggers {
        pollSCM('H/2 * * * *')
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Environment Check') {
            steps {
                bat 'java -version'
                bat 'docker version'
                bat 'docker compose version'
            }
        }

        stage('Build and Test') {
            steps {
                withEnv([
                    'SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:1/ci_only',
                    'SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:1',
                    'SPRING_DATA_REDIS_PORT=1'
                ]) {
                    dir('services/order-service') {
                        bat 'call mvnw.cmd -B clean verify'
                    }

                    dir('services/batch-service') {
                        bat 'call mvnw.cmd -B clean verify'
                    }

                    dir('services/telegram-bot') {
                        bat 'call mvnw.cmd -B clean verify'
                    }
                }
            }

            post {
                always {
                    junit testResults: '**/target/surefire-reports/TEST-*.xml',
                          allowEmptyResults: true
                }
            }
        }

        stage('Docker Build') {
            steps {
                bat 'docker build -t neighborroute/order-service:local services/order-service'

                bat 'docker build -t neighborroute/batch-service:local services/batch-service'

                bat 'docker build -t neighborroute/telegram-bot:local services/telegram-bot'
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([
                    string(
                        credentialsId: 'telegram-bot-token',
                        variable: 'TELEGRAM_BOT_TOKEN'
                    ),
                    string(
                        credentialsId: 'telegram-db-password',
                        variable: 'TELEGRAM_DB_PASSWORD'
                    )
                ]) {
                    dir('infra') {
                        bat '''
                            @echo off
                            docker compose up -d --wait --wait-timeout 120
                        '''

                        bat 'docker compose ps'
                    }
                }
            }
        }

        stage('Health Check') {
            steps {
                powershell '''
                    $ports = @(8080, 8081, 8082)

                    foreach ($port in $ports) {
                        $healthy = $false

                        for ($i = 0; $i -lt 30; $i++) {
                            try {
                                $response = Invoke-RestMethod `
                                    -Uri "http://localhost:$port/actuator/health" `
                                    -TimeoutSec 5 `
                                    -ErrorAction Stop

                                if ($response.status -eq "UP") {
                                    Write-Host "Service $port is UP"
                                    $healthy = $true
                                    break
                                }
                            }
                            catch {
                                Write-Host "Waiting for service $port"
                            }

                            Start-Sleep -Seconds 5
                        }

                        if (-not $healthy) {
                            throw "Service $port failed health check"
                        }
                    }
                '''
            }
        }
    }

    post {
        success {
            echo 'NeighborRoute successfully built and deployed!'
        }

        failure {
            echo 'Pipeline failed. Check stage logs.'
        }
    }
}
