# CloudSim CI/CD Pipeline

This document explains how to use the Continuous Integration and Continuous Deployment (CI/CD) setup for the CloudSim project, specifically for the AutoScaling example.

## Setup Overview

This project includes:
- GitHub Actions workflow (`.github/workflows/cloudsim-ci.yml`)
- Docker configuration (`Dockerfile` and `docker-compose.yml`)
- Local CI/CD script (`run-ci-cd.ps1`)

## Using GitHub Actions

The GitHub Actions workflow automatically runs when:
- Code is pushed to the `main` or `master` branch
- A pull request is opened against these branches
- You manually trigger the workflow

The workflow:
1. Builds the project
2. Runs tests
3. Executes the AutoScaling simulation
4. Creates deployment artifacts

### Manual Trigger

To manually trigger the workflow:
1. Go to the "Actions" tab in your GitHub repository
2. Select "CloudSim CI/CD" from the workflows list
3. Click "Run workflow"

## Running CI/CD Locally

### Using PowerShell Script

Run the included PowerShell script to execute the CI/CD pipeline locally:

```powershell
.\run-ci-cd.ps1
```

This script will:
1. Build the project
2. Run tests
3. Execute the AutoScaling simulation
4. Create a deployment package in the `deployment` folder
5. Build a Docker image if Docker is available

### Using Docker

To build and run the application using Docker:

```bash
# Build the Docker image
docker build -t cloudsim-autoscaling .

# Run the container
docker run cloudsim-autoscaling
```

Or use Docker Compose:

```bash
docker-compose up --build
```

## Deployment Artifacts

Deployment artifacts are stored in:
- GitHub Actions: Available as downloadable artifacts from the workflow run
- Local execution: Created in the `deployment` folder

## Customization

### Changing Java Version

To change the Java version:
1. Update the GitHub Actions workflow file (`.github/workflows/cloudsim-ci.yml`)
2. Update the Dockerfile `FROM` line
3. Rebuild as needed

### Adding New Examples

To add new simulation examples to the CI/CD process:
1. Create your example in the appropriate location
2. Update the GitHub Actions workflow to include your example
3. Update the local CI/CD script to include your example
4. Update the Dockerfile ENTRYPOINT if needed

## Troubleshooting

If you encounter issues:

1. **Build failures**: Check compilation errors and dependencies
2. **Test failures**: Verify test cases and environmental dependencies
3. **Docker issues**: Ensure Docker is installed and running

## Support

For questions or issues, please open a GitHub issue in the repository. 

name: CloudSim CI/CD

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]
  workflow_dispatch:
    # Manual trigger 

env:
  MAVEN_OPTS: "-Xmx2g"
  GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

- name: Run AutoScaling simulation
  run: |
    cd modules/cloudsim-examples
    # Use OS-agnostic classpath separator
    if [ "$RUNNER_OS" == "Windows" ]; then
      java -cp ".;../cloudsim/target/classes;target/classes" org.cloudbus.cloudsim.examples.AutoScaling
    else
      java -cp ".:../cloudsim/target/classes:target/classes" org.cloudbus.cloudsim.examples.AutoScaling
    fi
  shell: bash

- name: Cache Maven packages
  uses: actions/cache@v3
  with:
    path: ~/.m2
    key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
    restore-keys: ${{ runner.os }}-m2 

pages:
  needs: build
  runs-on: ubuntu-latest
  if: github.ref == 'refs/heads/main'
  
  steps:
    - name: Download simulation results
      uses: actions/download-artifact@v3
      with:
        name: simulation-results
        path: ./public
    
    - name: Setup Pages
      uses: actions/configure-pages@v3
    
    - name: Upload Pages artifact
      uses: actions/upload-pages-artifact@v2
      with:
        path: './public'
    
    - name: Deploy to GitHub Pages
      id: deployment
      uses: actions/deploy-pages@v2

- name: Send email notification
  if: always()
  uses: dawidd6/action-send-mail@v3
  with:
    server_address: ${{ secrets.MAIL_SERVER }}
    server_port: ${{ secrets.MAIL_PORT }}
    username: ${{ secrets.MAIL_USERNAME }}
    password: ${{ secrets.MAIL_PASSWORD }}
    subject: CloudSim CI/CD ${{ job.status }}
    body: CloudSim workflow completed with status ${{ job.status }}
    to: your-email@example.com
    from: GitHub Actions 