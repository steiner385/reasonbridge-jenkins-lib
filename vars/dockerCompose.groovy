#!/usr/bin/env groovy
/**
 * Docker Compose Helper
 * Executes docker compose commands with V1/V2 fallback support
 *
 * Features:
 * - Tries docker compose (V2) first, falls back to docker-compose (V1)
 * - Configurable compose file path
 * - Works with any docker compose command
 *
 * Usage:
 *   dockerCompose('up -d')
 *   dockerCompose('build --parallel', 'docker-compose.e2e.yml')
 *   dockerCompose('ps', 'docker-compose.test.yml')
 *   dockerCompose('down -v', 'docker-compose.e2e.yml')
 *
 * Background:
 *   Some Jenkins agents have docker-compose (V1) but not the docker command,
 *   so 'docker compose' (V2) fails. This helper ensures compatibility.
 */

def call(String command, String composeFile = 'docker-compose.yml') {
    sh """
        (docker compose -f ${composeFile} ${command} 2>/dev/null || \
         docker-compose -f ${composeFile} ${command})
    """
}

/**
 * Execute docker compose command but ignore errors (useful for cleanup)
 */
def safe(String command, String composeFile = 'docker-compose.yml') {
    sh """
        (docker compose -f ${composeFile} ${command} 2>/dev/null || \
         docker-compose -f ${composeFile} ${command} 2>/dev/null) || true
    """
}

return this
