# Gnist

> *Gnist* means *Spark* in Danish.

Apache Spark 4.2.0 fork, integrated into the Served Unified Data Platform.

## What is Gnist?

Gnist is our production Spark distribution, built for Kubernetes-native deployment on the Eden cluster. It powers the Unified Data Platform's batch processing, SQL analytics, and streaming pipelines.

**Based on:** Apache Spark 4.2.0
**Upstream:** [github.com/apache/spark](https://github.com/apache/spark)
**License:** Apache 2.0

## Quick Start

```bash
# Build from source (Kubernetes profile)
served gnist build

# Submit a Spark job
served gnist submit --class org.apache.spark.examples.SparkPi examples/jars/spark-examples.jar

# Run Spark SQL
served gnist sql "SELECT 1 + 1"

# Check running jobs
served gnist jobs

# Stream logs from a job
served gnist logs <jobId>
```

## Build

### Using served CLI (recommended)

```bash
served gnist build          # Maven build with K8s profile
served gnist image          # Build Docker image
```

### Manual Maven build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21
MAVEN_OPTS="-Xmx4g" ./build/mvn -Pkubernetes -DskipTests clean package
```

### Docker image

```bash
./bin/docker-image-tool.sh \
  -r registry.unifiedhq.ai/served/gnist \
  -t gnist-4.2.0 \
  build
```

## Deployment

Gnist runs on the Eden K8s cluster in the `served-gnist` namespace.

| Resource | Value |
|----------|-------|
| Namespace | `served-gnist` |
| Service Account | `gnist-driver` |
| K8s Master | `k8s://https://10.10.10.20:6443` |
| Image | `registry.unifiedhq.ai/served/gnist:gnist-4.2.0` |

### Default Resources

| Component | Memory | CPU |
|-----------|--------|-----|
| Driver | 2Gi (req) / 4Gi (limit) | 1 (req) / 2 (limit) |
| Executor | 4Gi (req) / 8Gi (limit) | 2 (req) / 4 (limit) |

## API

REST API via the ServedApp DevOps module:

```
POST   /api/devops/gnist/submit        # Submit job
GET    /api/devops/gnist/{jobId}        # Job status
GET    /api/devops/gnist/{jobId}/logs   # Stream logs
DELETE /api/devops/gnist/{jobId}        # Kill job
GET    /api/devops/gnist                # List all jobs
GET    /api/devops/gnist/cluster        # Cluster status
GET    /api/devops/gnist/health         # Health check
```

Real-time updates via SignalR hub `/hubs/devops` (subscribe to `GnistJobStatusChanged`).

## Forge Pipeline

The `.forge/workflows/gnist-build.yaml` pipeline handles:

1. Checkout with submodules
2. Maven build (`-Pkubernetes -DskipTests`)
3. Docker image build
4. Push to `registry.unifiedhq.ai`
5. K8s RBAC apply (optional)

## Configuration

See `.served/gnist.unified` in the ServedApp monorepo for all settings.

## Syncing with Upstream

```bash
git fetch upstream
git merge upstream/master
# Resolve conflicts, test, push
git push forge master
```

## Architecture

```
ServedApp (monorepo)
  ├── .served/gnist.unified          # Config
  ├── .forge/workflows/gnist-build.yaml  # CI/CD
  ├── forge/k8s/gnist-rbac.yaml      # K8s RBAC
  ├── Served/Apis/DevOps/
  │   ├── Controllers/GnistController.cs  # REST API
  │   ├── Services/GnistJobService.cs     # Job lifecycle
  │   └── Models/GnistModels.cs           # DTOs
  ├── Served/Tools/CLI/Commands/GnistCommand.cs  # CLI
  └── tools/gnist/                    # This repo (Spark fork)
```

---

*Part of the Served Unified Data Platform. Built by Thomas and Atlas.*
