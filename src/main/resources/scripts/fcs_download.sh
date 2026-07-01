#!/bin/bash
#
# Download and extract FCS CLI using the v2 API endpoint.
#

set -euo pipefail

# Configuration
readonly SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"
readonly USER_AGENT="fcs-cli-intellij-plugin"
readonly TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

# Trusted domains for FCS binary downloads (crowdstrike API + S3 redirect targets)
readonly TRUSTED_DOWNLOAD_DOMAINS=("crowdstrike.com" "crowdstrike.mil" "amazonaws.com")

# Function to get API base URL for region
get_region_base_url() {
    local region="$1"
    case "$region" in
        "us-1") echo "api.crowdstrike.com" ;;
        "us-2") echo "api.us-2.crowdstrike.com" ;;
        "eu-1") echo "api.eu-1.crowdstrike.com" ;;
        "us-gov-1") echo "api.laggar.gcw.crowdstrike.com" ;;
        "us-gov-2") echo "api.us-gov-2.crowdstrike.mil" ;;
        *) echo "api.crowdstrike.com" ;;
    esac
}

# Colors for output
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*" >&2
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $*"
}

# Function to get OAuth2 token
get_oauth_token() {
    local client_id="$1"
    local client_secret="$2" 
    local base_url="$3"
    
    local token_response http_code
    token_response=$(curl -sS -L -w '\n__HTTP_CODE__:%{http_code}' \
        -X POST "https://${base_url}/oauth2/token" \
        -H "Accept: application/json" \
        -H "User-Agent: $USER_AGENT" \
        --data-urlencode "client_id=$client_id" \
        --data-urlencode "client_secret=$client_secret" \
        --data-urlencode "grant_type=client_credentials" 2>&1)

    http_code=$(echo "$token_response" | grep '__HTTP_CODE__:' | cut -d: -f2)
    token_response=$(echo "$token_response" | grep -v '__HTTP_CODE__:')

    if ! echo "$token_response" | jq -e '.access_token' > /dev/null 2>&1; then
        log_error "Failed to get OAuth token (HTTP $http_code)"
        local api_error
        api_error=$(echo "$token_response" | jq -r '.errors[0].message // empty' 2>/dev/null)
        if [[ -n "$api_error" ]]; then
            log_error "API error: $api_error"
        elif [[ -z "$token_response" ]]; then
            log_error "No response — possible SSL or network failure connecting to $base_url"
        else
            log_error "Unexpected response: $(echo "$token_response" | head -c 200)"
        fi
        return 1
    fi
    
    echo "$token_response" | jq -r '.access_token'
}

# Function to detect OS and architecture separately
detect_platform() {
    local system=$(uname -s | tr '[:upper:]' '[:lower:]')
    local machine=$(uname -m | tr '[:upper:]' '[:lower:]')
    
    local os arch
    
    case "$system" in
        linux)
            os="linux"
            ;;
        darwin)
            os="darwin"
            ;;
        mingw*|cygwin*|msys*)
            os="windows"
            ;;
        *)
            log_warn "Unknown system: $system, defaulting to linux"
            os="linux"
            ;;
    esac
    
    case "$machine" in
        aarch64|arm64) arch="arm64" ;;
        x86_64|amd64) arch="amd64" ;;
        *)
            log_warn "Unknown architecture: $machine, defaulting to amd64"
            arch="amd64"
            ;;
    esac
    
    echo "${os} ${arch}"
}

# Function to call the v2 files-download API
get_fcs_download_info() {
    local token="$1"
    local base_url="$2"
    local platform="$3"
    local version="$4"
    
    # Parse OS and architecture from platform
    local os=$(echo "$platform" | cut -d' ' -f1)
    local arch=$(echo "$platform" | cut -d' ' -f2)
    
    local api_url="https://${base_url}/csdownloads/combined/files-download/v2"
    local filter
    
    # Build filter parameter
    if [[ -n "$version" ]]; then
        filter="category:'fcs'+os:'${os}'+arch:'${arch}'+file_version:'${version}'"
    else
        filter="category:'fcs'+os:'${os}'+arch:'${arch}'"
    fi
    
    # Simple URL encoding for the filter
    local encoded_filter
    encoded_filter=$(echo "$filter" | sed "s/+/%2B/g; s/:/%3A/g")
    
    local auth_header_file
    auth_header_file=$(mktemp "$TEMP_DIR/auth-header.XXXXXX")
    printf 'Authorization: Bearer %s\n' "$token" > "$auth_header_file"
    local response
    response=$(curl -s -X GET "$api_url?filter=${encoded_filter}&limit=100&sort=file_version%7Cdesc" \
        -H "accept: application/json" \
        -H "User-Agent: $USER_AGENT" \
        --header @"$auth_header_file")
    
    # Check if response contains errors
    if echo "$response" | jq -e '.errors' > /dev/null 2>&1; then
        log_error "API returned errors:"
        echo "$response" | jq -r '.errors[] | "  - \(.message)"'
        return 1
    fi
    
    # Check if we have resources
    if ! echo "$response" | jq -e '.resources[0]' > /dev/null 2>&1; then
        log_error "No resources found in API response"
        return 1
    fi
    
    echo "$response"
}

# Function to extract download info from API response (gets latest version)
extract_download_details() {
    local response="$1"
    local detail_type="$2"  # download_url, file_name, file_hash, version
    
    # API returns results sorted by file_version:desc, so first element is the latest
    case "$detail_type" in
        "download_url")
            echo "$response" | jq -r ".resources[0].download_info.download_url // empty"
            ;;
        "file_name")
            echo "$response" | jq -r ".resources[0].file_name // empty"
            ;;
        "file_hash")
            echo "$response" | jq -r ".resources[0].download_info.file_hash // .resources[0].file_hash // empty"
            ;;
        "version")
            echo "$response" | jq -r ".resources[0].file_version // empty"
            ;;
        *)
            echo "$response" | jq -r ".resources[0].$detail_type // empty"
            ;;
    esac
}

# Validate that a download URL belongs to a trusted domain
validate_download_url() {
    local url="$1"
    local host
    host=$(echo "$url" | sed -E 's|https?://([^/:]+).*|\1|')

    for trusted in "${TRUSTED_DOWNLOAD_DOMAINS[@]}"; do
        if [[ "$host" == "$trusted" || "$host" == *".$trusted" ]]; then
            return 0
        fi
    done

    log_error "Download host \"$host\" does not belong to a trusted domain"
    return 1
}

# Function to download file with hash validation
download_and_validate_file() {
    local download_url="$1"
    local file_name="$2"
    local expected_hash="$3"
    local output_dir="$4"
    
    local output_path="$output_dir/$file_name"
    
    if ! curl -sL -H "User-Agent: $USER_AGENT" -o "$output_path" "$download_url"; then
        log_error "Download failed"
        return 1
    fi
    
    local file_hash
    if command -v sha256sum > /dev/null; then
        file_hash=$(sha256sum "$output_path" | cut -d' ' -f1)
    elif command -v shasum > /dev/null; then
        file_hash=$(shasum -a 256 "$output_path" | cut -d' ' -f1)
    else
        log_error "No SHA256 utility available"
        return 1
    fi
    
    # Convert hashes to lowercase for comparison
    local file_hash_lower=$(echo "$file_hash" | tr '[:upper:]' '[:lower:]')
    local expected_hash_lower=$(echo "$expected_hash" | tr '[:upper:]' '[:lower:]')
    
    if [[ "$file_hash_lower" == "$expected_hash_lower" ]]; then
        echo "$output_path"
        return 0
    else
        log_error "Hash mismatch!"
        log_error "Expected: $expected_hash"
        log_error "Got:      $file_hash"
        return 1
    fi
}

# Function to extract and setup FCS binary
extract_and_setup_fcs() {
    local archive_path="$1"
    local bin_path="$2"
    
    # Create bin directory
    mkdir -p "$bin_path"
    
    local fcs_binary="fcs"
    if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == CYGWIN* || "$(uname -s)" == MSYS* ]]; then
        fcs_binary="fcs.exe"
    fi
    
    local fcs_path="$bin_path/$fcs_binary"
    
    # Remove existing FCS binary to prevent version conflicts
    if [[ -f "$fcs_path" ]]; then
        rm -f "$fcs_path"
        log_info "Removed existing FCS binary"
    fi
    
    # Canonicalize temp dir to guard against symlink-based escapes
    local canon_temp
    if ! canon_temp=$(cd "$TEMP_DIR" && pwd -P) || [[ -z "$canon_temp" ]]; then
        log_error "Failed to resolve temp directory path"
        return 1
    fi

    # Extract based on file extension
    if [[ "$archive_path" == *.tar.gz ]]; then
        # Extract tar.gz with path traversal guards
        if ! tar -xzf "$archive_path" -C "$TEMP_DIR" \
                --exclude='*..'; then
            log_error "Failed to extract tar.gz file"
            return 1
        fi

        # Verify no entry escaped the temp directory
        while IFS= read -r -d '' entry; do
            local resolved
            resolved=$(cd "$(dirname "$entry")" && pwd -P)/$(basename "$entry")
            if [[ "$resolved" != "$canon_temp"/* && "$resolved" != "$canon_temp" ]]; then
                log_error "Archive entry escaped temp directory: $entry"
                return 1
            fi
        done < <(find "$TEMP_DIR" -print0)

        # Find the fcs binary
        local extracted_fcs
        extracted_fcs=$(find "$TEMP_DIR" -name "fcs" -o -name "fcs.exe" | head -n1)

        if [[ -z "$extracted_fcs" ]]; then
            log_error "FCS binary not found in archive"
            return 1
        fi

        cp "$extracted_fcs" "$fcs_path"

    elif [[ "$archive_path" == *.zip ]]; then
        # Pre-validate zip member paths before extraction (zipinfo ships with unzip, no new dependency)
        local zip_members
        if ! zip_members=$(zipinfo -1 "$archive_path"); then
            log_error "Failed to list zip archive contents"
            return 1
        fi
        while IFS= read -r member; do
            if [[ "$member" == /* || "$member" == *../* || "$member" == */.. || "$member" == ".." ]]; then
                log_error "Archive member has suspicious path: $member"
                return 1
            fi
        done <<< "$zip_members"

        # Extract zip with path traversal guards
        if ! unzip -q "$archive_path" -d "$TEMP_DIR"; then
            log_error "Failed to extract zip file"
            return 1
        fi

        # Verify no entry escaped the temp directory (catches symlink-based escapes)
        while IFS= read -r -d '' entry; do
            local resolved
            resolved=$(cd "$(dirname "$entry")" && pwd -P)/$(basename "$entry")
            if [[ "$resolved" != "$canon_temp"/* && "$resolved" != "$canon_temp" ]]; then
                log_error "Archive entry escaped temp directory: $entry"
                return 1
            fi
        done < <(find "$TEMP_DIR" -print0)

        # Find the fcs binary
        local extracted_fcs
        extracted_fcs=$(find "$TEMP_DIR" -name "fcs" -o -name "fcs.exe" | head -n1)

        if [[ -z "$extracted_fcs" ]]; then
            log_error "FCS binary not found in archive"
            return 1
        fi

        cp "$extracted_fcs" "$fcs_path"
    else
        log_error "Unsupported archive format: $archive_path"
        return 1
    fi
    
    # Make executable on Unix-like systems
    if [[ "$(uname -s)" != MINGW* && "$(uname -s)" != CYGWIN* && "$(uname -s)" != MSYS* ]]; then
        chmod +x "$fcs_path"
    fi
    
    # Create log directory that FCS expects
    local log_dir="$HOME/.crowdstrike/log"
    mkdir -p "$log_dir"
    
    echo "$fcs_path"
}

# Function to set GitHub Actions output
set_github_output() {
    local name="$1"
    local value="$2"
    
    if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
        echo "$name=$value" >> "$GITHUB_OUTPUT"
    fi
}

# Main function
main() {
    # Get inputs from environment (supports both GitHub Actions and plugin usage)
    local bin_path="${INPUT_BIN_PATH:-${RUNNER_TEMP:-${HOME}/.local/bin}}"
    local client_id="${FALCON_CLIENT_ID:-}"
    local client_secret="${FALCON_CLIENT_SECRET:-}"
    local region="${FALCON_CLOUD:-us-1}"
    local version="${FCS_VERSION:-}"
    local max_version="${FCS_MAX_VERSION:-}"
    unset FALCON_CLIENT_ID FALCON_CLIENT_SECRET

    # If no explicit version requested and a max version is set, default to max
    # so we never download a version the plugin hasn't been validated against
    if [[ -z "$version" && -n "$max_version" ]]; then
        version="$max_version"
        log_info "No version specified — defaulting to latest compatible: $version"
    fi

    # Validate required inputs
    if [[ -z "$client_id" ]]; then
        log_error "FALCON_CLIENT_ID environment variable is required"
        exit 1
    fi
    
    if [[ -z "$client_secret" ]]; then
        log_error "FALCON_CLIENT_SECRET environment variable is required"
        exit 1
    fi
    
    # Get API base URL
    local base_url
    base_url=$(get_region_base_url "$region")
    
    # Check required tools
    for tool in curl jq tar unzip zipinfo; do
        if ! command -v "$tool" > /dev/null; then
            log_error "Required tool not found: $tool"
            exit 1
        fi
    done
    
    # Step 1: Get OAuth token
    local token
    if ! token=$(get_oauth_token "$client_id" "$client_secret" "$base_url"); then
        log_error "Failed to get OAuth token"
        exit 1
    fi
    
    # Step 2: Detect platform
    local platform
    platform=$(detect_platform)
    log_info "Detected platform: $platform"
    
    # Step 3: Get download info from v2 API
    local download_response
    if ! download_response=$(get_fcs_download_info "$token" "$base_url" "$platform" "$version"); then
        log_error "Failed to get FCS download information"
        exit 1
    fi
    
    # Step 4: Extract download details
    local download_url file_name file_hash file_version
    download_url=$(extract_download_details "$download_response" "download_url")
    file_name=$(extract_download_details "$download_response" "file_name")
    file_hash=$(extract_download_details "$download_response" "file_hash")
    file_version=$(extract_download_details "$download_response" "version")
    
    if [[ -z "$download_url" || -z "$file_name" || -z "$file_hash" ]]; then
        log_error "Missing required download details"
        log_error "Download URL: $download_url"
        log_error "File Name: $file_name"
        log_error "File Hash: $file_hash"
        exit 1
    fi

    # Validate download URL is from a trusted domain before fetching
    if ! validate_download_url "$download_url"; then
        exit 1
    fi

    log_info "Using FCS version: $file_version - $file_name"
    
    # Step 5: Download and validate file
    local downloaded_file
    if ! downloaded_file=$(download_and_validate_file "$download_url" "$file_name" "$file_hash" "$TEMP_DIR"); then
        log_error "Failed to download or validate FCS file"
        exit 1
    fi
    
    # Step 6: Extract and setup FCS binary
    local fcs_binary_path
    if ! fcs_binary_path=$(extract_and_setup_fcs "$downloaded_file" "$bin_path"); then
        log_error "Failed to extract and setup FCS binary"
        exit 1
    fi
    
    # Step 7: Set GitHub Actions outputs
    set_github_output "FCS_BIN" "$fcs_binary_path"
    
    # Add to GitHub Actions PATH
    if [[ -n "${GITHUB_PATH:-}" ]]; then
        echo "$bin_path" >> "$GITHUB_PATH"
        log_info "Added $bin_path to GitHub PATH"
    fi
    
    # Step 8: Automatically add FCS to PATH permanently
    local path_script="$bin_path/setup_fcs_path.sh"
    cat > "$path_script" << EOF
#!/bin/bash
# Source this file to add FCS to your PATH permanently
export PATH="$bin_path:\$PATH"
echo "FCS has been added to your PATH"
EOF
    chmod +x "$path_script"
    
    # Detect user's shell and add PATH automatically
    local shell_profile=""
    local shell_name=""
    
    # Check what shell the user is using
    if [[ -n "${ZSH_VERSION:-}" ]] || [[ "$SHELL" == */zsh ]]; then
        shell_profile="$HOME/.zshrc"
        shell_name="zsh"
    elif [[ -n "${BASH_VERSION:-}" ]] || [[ "$SHELL" == */bash ]]; then
        shell_profile="$HOME/.bashrc"
        shell_name="bash"
    else
        # Default to detecting from SHELL variable
        case "$SHELL" in
            */zsh)
                shell_profile="$HOME/.zshrc"
                shell_name="zsh"
                ;;
            */bash)
                shell_profile="$HOME/.bashrc"
                shell_name="bash"
                ;;
            *)
                # Fallback to zsh since it's most common on macOS
                shell_profile="$HOME/.zshrc"
                shell_name="zsh (detected as fallback)"
                ;;
        esac
    fi
    
    # Check if PATH export already exists in shell profile
    local path_export="export PATH=\"$bin_path:\$PATH\""
    local path_already_exists=false
    
    if [[ -f "$shell_profile" ]] && grep -Fq "export PATH=\"$bin_path:" "$shell_profile"; then
        path_already_exists=true
    fi
    
    if [[ "$path_already_exists" == false ]]; then
        # Add PATH export to shell profile
        echo "" >> "$shell_profile"
        echo "# Added by FCS CLI installer" >> "$shell_profile"
        echo "$path_export" >> "$shell_profile"
        log_success "Automatically added FCS to PATH in $shell_profile ($shell_name)"
    else
        log_info "FCS PATH already exists in $shell_profile"
    fi
    
    log_success "FCS binary ready at: $fcs_binary_path"
    echo "$fcs_binary_path"
    
    # Output the export command for current session
    echo "# Run this command to add FCS to your current PATH:"
    echo "export PATH=\"$bin_path:\$PATH\""
}

# Run main function if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
