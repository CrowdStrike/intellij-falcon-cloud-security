# CrowdStrike FCS CLI Plugin

---

## Overview
This plugin integrates CrowdStrike's Falcon Cloud Security (FCS) CLI tool directly into your IntelliJ IDE, providing Infrastructure as Code (IaC) security scanning capabilities. Annotate your Ansible, Docker, Kubernetes, and other IaC files to identify security misconfigurations in your code before deployment.

---

## Supported File Types
- Terraform (`.tf`, `.tfvars`)
- CloudFormation (`.json`, `.yaml`, `.yml`)
- Kubernetes manifests (`.yaml`, `.yml`)
- Helm charts
- Docker files (`Dockerfile`)
- Serverless Framework files (`serverless.yml`)
- Azure Resource Manager templates (`.json`)
- Bicep files (`.bicep`)
- Pulumi (TypeScript, JavaScript, Python, Go)

---

## Getting Started
1. **Enable the Plugin:** Check the 'Plugin Enabled' checkbox at the top of the Configuration tab
2. **Download FCS CLI:** If not already installed, use the 'Download FCS CLI' button
3. **Configure Scan Settings:** Set your target path and severity levels in the Configuration tab
4. **Start Scanning:** Open supported filetypes within your target path to automatically scan and annotate

---

## Manage FCS CLI Binary
The plugin will automatically check common paths for the FCS CLI Binary. If found, `FCS Binary Status` will show `Available`.

### Download FCS CLI
If the FCS Binary is `Not found` you can download the binary directly from the plugin.
1. Click **Download FCS CLI**
2. Configure download configuration in the prompt
3. Click **Okay**

The binary will be installed to `${HOME}/.local/bin`

### Update the FCS CLI
If the FCS Binary is `Available` you can download the latest binary directly from the plugin.
1. Click **Update FCS CLI**
2. Configure the download configuration in the prompt
3. Click **Okay**

The binary will be replaced in `${HOME}/.local/bin`

**Note:** Credentials are never stored and must be re-entered each time.

---

## Scan Configuration

### Target Path
Specify the directory to allow scans, defaults to project root. All supported filetypes within the target path directory and child directories will be automatically scanned when opened.

### Severities
Choose which severity levels to include in scans (Critical, High, Medium, Low, Info).

### Exclude Secrets
When enabled, excludes secret detection from scans.

### Auto-scan on save
Automatically runs scans when supported files within the Target Path are saved.

---

## Understanding Scan Results
Scan results appear as annotations in your code and in the problems area of the IDE:
- **Critical Findings:** 🔴 Severe security risks that need immediate attention
- **High Issues:** 🟠 Important security issues that should be addressed
- **Medium Findings:** 🟡 Moderate security concerns
- **Low and Informational Findings:** ℹ️ Low-risk and informational findings and best practices

---

## Troubleshooting

### FCS CLI Not Found
- Click 'Download FCS CLI' to install the tool
- Ensure your API credentials are correctly configured

### Annotation/Problems Missing
- Verify the opened file is a supported filetype and within the Target Path
- Check that the plugin is enabled

### Version or Compatibility Issues

Check the FCS Binary Status section at the top of the Configuration tab — it shows the CLI version in use and whether it falls within the supported range.

- **Below minimum:** click **Download FCS CLI** to install a compatible version
- **Above maximum:** the CLI is newer than this plugin has been validated against — update the plugin
- **Compatible but not on latest:** click **Download FCS CLI** to get the latest compatible version

### Scan Fails After Upgrading the FCS CLI
Upgrading the CLI binary without migrating your configuration can cause unexpected failures. Run the following in a terminal to migrate your config to the latest format:

```
fcs migrate-config
```

If you are upgrading from FCS CLI v2, the plugin's **Download FCS CLI** button will run this migration automatically.

### Performance
- Consider using Target Path to exclude directories from scans
- Disable auto-scan for large projects if needed
- Use severity filtering to focus on critical issues

---

## Support
For support and additional information:
- **CrowdStrike Support:** Contact your CrowdStrike representative
- **FCS CLI Documentation:** Available in your CrowdStrike console
- **Plugin Issues:** Check the plugin configuration and scan output for details
