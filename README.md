# Messy: Android Messenger with XMTP & Web3 Integration

Messy is a privacy-focused, decentralized Android messaging application. It leverages the XMTP protocol for secure messaging and integrates multiple Web3 services to provide a seamless user experience.

## 🚀 Features

- **Decentralized Messaging**: Peer-to-peer secure messaging via the [XMTP](https://xmtp.org/) protocol.
- **Web3 Authentication**: Social login integration (Telegram, etc.) via [Web3Auth](https://web3auth.io/).
- **Smart Contract Wallets**: Abstracted account management using [Pimlico](https://pimlico.io/) for gasless transactions on Polygon.
- **Video & Audio Calls**: High-quality decentralized communication powered by [Huddle01](https://huddle01.com/).
- **Cross-Platform Wallet Support**: Connect any mobile wallet using [WalletConnect](https://walletconnect.com/).
- **Privacy Features**: Native support for DPI bypass and VPN for censored networks.

---

## 🛠 Self-Deployment Guide

To run Messy, you must configure your own API keys for the third-party services. Follow these steps to set up your environment.

### 1. Web3Auth Setup
Web3Auth handles the social login flow and private key management.

1. Create a project on the [Web3Auth Dashboard](https://dashboard.web3auth.io/).
2. **Network**: Use `Sapphire Devnet` (as configured in `Web3AuthManager.kt`).
3. **Redirect URI**: Add `web3auth://com.example.messenger` to your allowlist.
4. **Custom Verifier**:
   - Create a Custom Verifier for Telegram.
   - **Verifier ID**: Choose a unique ID (e.g., `messy-telegram-yourname`).
   - **Login Provider**: JWT
   - **JWT Domain**: Use your backend URL (e.g., `https://your-backend.com`).
   - **JWKS Endpoint**: Point this to your backend's JWKS endpoint (e.g., `https://your-backend.com/.well-known/jwks.json`).

### 2. Huddle01 Setup
Huddle01 provides the infrastructure for video and audio calls.

1. Sign up at the [Huddle01 Dashboard](https://huddle01.com/dashboard).
2. Create a new project and obtain your **Project ID** and **API Key**.
3. This app uses the **V2 API** for room creation and token generation. Ensure your project has access to these features.

### 3. WalletConnect Setup
WalletConnect allows users to pair their existing EOA wallets.

1. Register at [WalletConnect Cloud](https://cloud.walletconnect.com).
2. Create a new project to get your **Project ID**.
3. **Namespaces**: The app requests `eip155` (Ethereum) methods like `personal_sign`.

### 4. Pimlico Setup
Pimlico is used for Smart Contract Wallet (SCW) deployment and gas sponsorship.

1. Obtain an API key from the [Pimlico Dashboard](https://dashboard.pimlico.io).
2. The app is currently set to use **Polygon (Chain ID 137)**. Ensure your Pimlico account is funded if you plan to sponsor transactions.

---

## ⚙️ Configuration (`local.properties`)

The project uses `local.properties` to securely store API keys. This file is ignored by Git.

Add the following lines to `<project-root>/local.properties`:

```properties
# Web3Auth Client ID
WEB3AUTH_CLIENT_ID=your_web3auth_client_id

# Huddle01 Credentials
HUDDLE_PROJECT_ID=your_huddle_project_id
HUDDLE_API_KEY=your_huddle_api_key

# WalletConnect Project ID
WC_PROJECT_ID=your_walletconnect_project_id

# Pimlico API Key
PIMLICO_API_KEY=your_pimlico_api_key

# Web3Auth Verifier ID (Matches your dashboard)
WEB3AUTH_VERIFIER_ID=messy-telegram-id

# Backend Domain (Where your Telegram bot verifies JWTs)
BACKEND_DOMAIN=https://your-backend.com
```

---

## 🏗 Building the App

1. **Clone the repo**: `git clone https://github.com/your-repo/messy-android.git`
2. **Open in Android Studio**: Use the latest version for Hedgehog or Iguana.
3. **Gradle Sync**: Ensure you have a stable internet connection for downloading dependencies.
4. **Run**: Deploy to a physical device or emulator (API 26+).

> [!IMPORTANT]
> If you change the package name (`com.example.messenger`), you **MUST** update the Redirect URIs in your Web3Auth and WalletConnect dashboards.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
