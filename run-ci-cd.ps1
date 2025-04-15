# PowerShell script to run CI/CD pipeline locally
Write-Host "Starting CloudSim CI/CD pipeline locally..." -ForegroundColor Green

# Build the project
Write-Host "Building project..." -ForegroundColor Cyan
mvn clean install -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# Run tests
Write-Host "Running tests..." -ForegroundColor Cyan
mvn test
if ($LASTEXITCODE -ne 0) {
    Write-Host "Tests failed!" -ForegroundColor Red
    exit 1
}

# Run AutoScaling simulation
Write-Host "Running AutoScaling simulation..." -ForegroundColor Cyan
Push-Location modules/cloudsim-examples
java -cp ".;../cloudsim/target/classes;target/classes" org.cloudbus.cloudsim.examples.AutoScaling
if ($LASTEXITCODE -ne 0) {
    Write-Host "Simulation failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}
Pop-Location

# Create a deployment package
Write-Host "Creating deployment package..." -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path deployment | Out-Null
Copy-Item modules/cloudsim-examples/target/*.jar -Destination deployment/ -Force
Copy-Item modules/cloudsim/target/*.jar -Destination deployment/ -Force

# Build Docker image if Docker is available
if (Get-Command docker -ErrorAction SilentlyContinue) {
    Write-Host "Building Docker image..." -ForegroundColor Cyan
    docker build -t cloudsim-autoscaling:latest .
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker build failed!" -ForegroundColor Red
        exit 1
    }
    
    Write-Host "Docker image built successfully! You can run it with: docker run cloudsim-autoscaling:latest" -ForegroundColor Green
} else {
    Write-Host "Docker not found. Skipping Docker image build." -ForegroundColor Yellow
}

Write-Host "CI/CD pipeline completed successfully!" -ForegroundColor Green
Write-Host "Deployment artifacts available in the 'deployment' folder." -ForegroundColor Green 