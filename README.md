# CrowdStrike FCS CLI Plugin for IntelliJ IDEA

[![CrowdStrike](https://img.shields.io/badge/CrowdStrike-Falcon%20Cloud%20Security-red)](https://www.crowdstrike.com/products/cloud-security/)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-Plugin-blue)](https://plugins.jetbrains.com/)

An IntelliJ IDEA plugin that integrates CrowdStrike's Falcon Cloud Security (FCS) CLI to provide Infrastructure as Code (IaC) security scanning capabilities directly within your IDE.

## Features

### 🔍 **Real-time IaC Security Scanning**
- Scan Terraform, CloudFormation, Kubernetes, and other IaC files
- Support for multiple file formats: JSON, YAML, XML, Dockerfile, HCL, Bicep, Properties
- Inline security annotations and highlighting
- Integration with IntelliJ's Problems view

### 🛠️ **IDE Integration**
- Dedicated tool window for FCS CLI management
- Configurable scan settings and severity filtering
- Auto-scan on file save (optional)

### ⚙️ **Binary Management**
- Manage FCS CLI binary download, installation and updates

### 🎯 **Customizable Scanning**
- Configurable target paths
- Severity level filtering (Critical, High, Medium, Low, Info)
- Option to exclude secrets scanning

## Installation

### Prerequisites
- IntelliJ IDEA 2025.2 or later

### Create a CrowdStrike API Client

> [!NOTE]
> API clients are granted one or more API scopes. Scopes allow access to specific CrowdStrike APIs and describe the actions that an API client can perform. To create an API client, see [API Clients and Keys](https://falcon.crowdstrike.com/login/?unilogin=true&next=/api-clients-and-keys).

The following API scopes are available:

| Scope | Permission |
|---------|-------------|
| Cloud Security Tools Download | *READ* |
| Infrastructure as Code | *READ* & *WRITE* |

### From Source
1. Clone this repository
2. Open the project in IntelliJ IDEA
3. Run the `buildPlugin` Gradle task
4. Install the generated plugin file from `build/distributions/`

### Plugin Installation
1. Go to `File` → `Settings` → `Plugins`
2. Click `⚙️` → `Install Plugin from Disk...`
3. Select the built plugin ZIP file
4. Restart IntelliJ IDEA

## Configuration

### CrowdStrike API Credentials

The plugin requires CrowdStrike Falcon API credentials. You can provide them in two ways:

#### Option 1: Environment Variables
Set the following environment variables and restart IntelliJ IDEA:

```bash
export FALCON_CLIENT_ID="your-client-id"
export FALCON_CLIENT_SECRET="your-client-secret"
export FALCON_CLOUD="us-1"  # Optional: us-1, us-2, eu-1, etc.
export FCS_VERSION="latest"  # Optional: specific version
export HTTPS_PROXY="proxy-url"  # Optional: if using proxy
```

#### Option 2: Dialog Prompt
If environment variables are not set, the plugin will prompt for credentials when downloading the FCS CLI binary.

### Plugin Settings

Access the FCS CLI tool window via:
- `View` → `Tool Windows` → `CrowdStrike FCS CLI`
- Or click the CrowdStrike icon in the right tool window bar

Configure the following settings:
- **Plugin Enabled**: Toggle plugin functionality
- **Target Path**: Directory or file to scan (defaults to project root)
- **Severities**: Select which severity levels to report
- **Exclude Secrets**: Skip secrets detection in scans
- **Auto-scan on save**: Automatically scan files when saved
- **Scan Timeout**: Timeout for CLI scans in seconds. Increase this for large workspaces or slow machines. Defaults to 300 seconds.
- **Platforms**: Restrict scans to specific IaC platforms. Leave empty to scan all platforms. Valid values are `Ansible`, `AzureResourceManager`, `CloudFormation`, `Crossplane`, `DockerCompose`, `Dockerfile`, `GoogleDeploymentManager`, `Kubernetes`, `OpenAPI`, `Pulumi`, `ServerlessFW`, and `Terraform`.

## Usage

### Initial Setup
1. Open the CrowdStrike FCS CLI tool window
2. Click "Download FCS CLI" to install the binary
3. Configure your scan settings as needed
4. Enable the plugin if not already enabled

### Scanning Files
- **Scan-on-open**: Scans will invoke automatically when an IaC file within the Target Path is opened
- **Auto-scan**: Enable "Auto-scan on save" to scan files and update annotations automatically when saved
- **View Results**: Security issues appear as inline annotations and in the Problems view

### Understanding Results
- Security issues are highlighted directly in the editor
- Severity levels are color-coded and filterable
- Detailed information appears in the Problems view
- Raw scan output is available in the tool window

## Development

### Project Structure

```
src/
├── main/
│   ├── kotlin/com/crowdstrike/fcscliplugin/
│   │   ├── FCSToolWindowFactory.kt          # Main UI component
│   │   ├── annotators/
│   │   │   └── FCSExternalAnnotator.kt      # Real-time annotations
│   │   ├── filetypes/
│   │   │   └── FCSFileTypes.kt              # Custom file type support
│   │   ├── inspections/
│   │   │   └── FCSInspection.kt             # Problems view integration
│   │   └── services/
│   │       ├── FCSBinaryService.kt          # Binary management
│   │       ├── FCSConfigurationService.kt   # Settings persistence
│   │       ├── FCSFileScanTriggerService.kt # Auto-scan functionality
│   │       └── FCSResultsService.kt         # Results processing
│   └── resources/
│       ├── META-INF/plugin.xml              # Plugin manifest
│       ├── icons/                           # UI icons
│       ├── messages/                        # Localization
│       └── scripts/fcs_download.sh          # Binary download script
├── test/
│   └── kotlin/com/crowdstrike/fcscliplugin/ # Unit test suite
```

### Building the Plugin

```bash
# Build the plugin
./gradlew buildPlugin

# Run in IDE for testing
./gradlew runIde

# Run tests
./gradlew test

# Verify plugin compatibility
./gradlew verifyPlugin
```

### Run Configurations

The project includes predefined run configurations:

| Configuration | Description |
|---------------|-------------|
| Run Plugin | Launches IntelliJ IDEA with the plugin installed for testing |
| Run Tests | Executes the plugin test suite |
| Run Verifications | Validates plugin compatibility with specified IntelliJ versions |

## Troubleshooting

### FCS CLI Not Found
- Ensure you have valid CrowdStrike API credentials
- Click "Download FCS CLI" in the tool window
- Check that the binary was downloaded to the correct location

### Credentials Issues
- Verify your Client ID and Client Secret are correct
- Ensure you have proper API Scope in CrowdStrike Falcon
- Try setting environment variables and restarting IntelliJ

### Scan Results Not Appearing
- Check that the plugin is enabled in the tool window
- Verify the target path is correct
- Ensure the file types are supported
- Check the Idea log for detailed error messages

### Performance Issues
- Disable auto-scan on save for large projects
- Adjust severity filters to reduce noise
- Use specific target paths instead of targeting entire project

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup instructions, how to run tests locally, and the release process.

## License

See [LICENSE.txt](LICENSE.txt).

## Support

For support with this plugin:
- Check the [CrowdStrike Documentation](https://www.crowdstrike.com/products/cloud-security/)
- Review IntelliJ Plugin Development guides
- Submit issues via the [project repository](https://github.com/CrowdStrike/intellij-falcon-cloud-security)

## Related Links

- [CrowdStrike Falcon Cloud Security](https://www.crowdstrike.com/products/cloud-security/)
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [FCS CLI Documentation](https://www.crowdstrike.com/products/cloud-security/)
