<table align="center">
  <tr>
    <td align="center" width="120">
      <img src="assets/osnm-z.svg" alt="OSNM-Z Mint Bot logo" width="100" />
    </td>
    <td align="center">
      <h1>OSNM-Z</h1>
      <h3>Single-wallet Python CLI for OpenSea NFT mints</h3>
    </td>
  </tr>
</table>

A Python CLI tool for minting ERC-721 NFTs from OpenSea SeaDrop collections. Each
run uses **one wallet and one selected phase**, supporting public sales, signed
allowlists, and Merkle allowlists. The configured wallet pays for the mint and gas
and receives the NFTs.

<h2 align="center">Installation</h2>

The sections below cover the major operating systems and commonly used terminals:
Windows with PowerShell or Command Prompt, Linux with Bash, and macOS with Zsh or
Bash. Follow the section for your operating system and terminal, completing each
step in order.

<h3 align="center">Windows</h3>

<h4 align="center">PowerShell</h4>

1. Open Windows PowerShell 5.1 or PowerShell 7 and check whether Git is installed:

   ```powershell
   git --version
   ```

2. If Git is unavailable, install it with WinGet:

   ```powershell
   winget install --id Git.Git -e --source winget
   ```

   For manual installation, download and run the
   [official Git for Windows installer](https://git-scm.com/install/windows).
   Close and reopen PowerShell after installing Git, then run `git --version` again.

3. Install uv:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "irm https://astral.sh/uv/install.ps1 | iex"
   ```

4. Close and reopen PowerShell, then confirm that uv is available:

   ```powershell
   uv --version
   ```

5. Clone the repository:

   ```powershell
   git clone https://github.com/zunmax/osnm-z.git
   ```

6. Enter the cloned project directory:

   ```powershell
   Set-Location -LiteralPath '.\osnm-z'
   ```

7. Install Python 3.12 through uv:

   ```powershell
   uv python install 3.12
   ```

8. Create the project environment from the locked dependencies:

   ```powershell
   uv sync --frozen --python 3.12
   ```

9. Create your private configuration file:

   ```powershell
   Copy-Item -LiteralPath '.env.example' -Destination '.env'
   ```

10. Open the configuration file in Notepad:

    ```powershell
    notepad.exe .env
    ```

    Follow [Wallet setup](#wallet-setup) to fill in the required values and save the file.

<h4 align="center">Command Prompt (CMD)</h4>

1. Open Command Prompt and check whether Git is installed:

   ```bat
   git --version
   ```

2. If Git is unavailable, install it with WinGet:

   ```bat
   winget install --id Git.Git -e --source winget
   ```

   For manual installation, download and run the
   [official Git for Windows installer](https://git-scm.com/install/windows).
   Close and reopen Command Prompt after installing Git, then run `git --version` again.

3. Install uv through Windows PowerShell:

   ```bat
   powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "irm https://astral.sh/uv/install.ps1 | iex"
   ```

4. Close and reopen Command Prompt, then confirm that uv is available:

   ```bat
   uv --version
   ```

5. Clone the repository:

   ```bat
   git clone https://github.com/zunmax/osnm-z.git
   ```

6. Enter the cloned project directory:

   ```bat
   cd /d "osnm-z"
   ```

7. Install Python 3.12 through uv:

   ```bat
   uv python install 3.12
   ```

8. Create the project environment from the locked dependencies:

   ```bat
   uv sync --frozen --python 3.12
   ```

9. Create your private configuration file:

   ```bat
   copy ".env.example" ".env"
   ```

10. Open the configuration file in Notepad:

    ```bat
    notepad.exe .env
    ```

    Follow [Wallet setup](#wallet-setup) to fill in the required values and save the file.

<h3 align="center">Linux</h3>

1. Open a Bash terminal and check whether Git is installed:

   ```bash
   git --version
   ```

2. If Git is unavailable, follow the instructions for your distribution.

   On Debian or Ubuntu, update the package index:

   ```bash
   sudo apt update
   ```

   Then install Git:

   ```bash
   sudo apt install git
   ```

   On Fedora, install Git with DNF instead:

   ```bash
   sudo dnf install git
   ```

   For other distributions or manual installation, follow the
   [official Git for Linux instructions](https://git-scm.com/install/linux).
   After installing Git, run `git --version` again.

3. Install uv:

   ```bash
   curl -LsSf https://astral.sh/uv/install.sh | sh
   ```

   If `curl` is unavailable, install uv with `wget` instead:

   ```bash
   wget -qO- https://astral.sh/uv/install.sh | sh
   ```

4. Close and reopen the terminal, then confirm that uv is available:

   ```bash
   uv --version
   ```

5. Clone the repository:

   ```bash
   git clone https://github.com/zunmax/osnm-z.git
   ```

6. Enter the cloned project directory:

   ```bash
   cd osnm-z
   ```

7. Install Python 3.12 through uv:

   ```bash
   uv python install 3.12
   ```

8. Create the project environment from the locked dependencies:

   ```bash
   uv sync --frozen --python 3.12
   ```

9. Create your private configuration file:

   ```bash
   cp .env.example .env
   ```

10. Open `.env` in your preferred plain-text editor, then follow
    [Wallet setup](#wallet-setup).

<h3 align="center">macOS</h3>

1. Open Terminal using Zsh or Bash and check whether Git is installed:

   ```sh
   git --version
   ```

2. If Git is unavailable, install Apple's Command Line Tools, which include Git:

   ```sh
   xcode-select --install
   ```

   For manual installation or other package-manager options, follow the
   [official Git for macOS instructions](https://git-scm.com/install/mac).
   After installing Git, run `git --version` again.

3. Install uv:

   ```sh
   curl -LsSf https://astral.sh/uv/install.sh | sh
   ```

4. Close and reopen Terminal, then confirm that uv is available:

   ```sh
   uv --version
   ```

5. Clone the repository:

   ```sh
   git clone https://github.com/zunmax/osnm-z.git
   ```

6. Enter the cloned project directory:

   ```sh
   cd osnm-z
   ```

7. Install Python 3.12 through uv:

   ```sh
   uv python install 3.12
   ```

8. Create the project environment from the locked dependencies:

   ```sh
   uv sync --frozen --python 3.12
   ```

9. Create your private configuration file:

   ```sh
   cp .env.example .env
   ```

10. Open `.env` in your preferred plain-text editor and follow [Wallet setup](#wallet-setup).

<h2 id="wallet-setup" align="center">Wallet setup</h2>

The setup commands copy [`.env.example`](.env.example) to `.env`. Run the copy
command only during first-time setup so an existing `.env` is not overwritten.
Edit `.env`, set the required values, and save it before running the tool:

```dotenv
WALLET_KEY=0x<64-hex-character-private-key>
RPC_URL=https://your-chain-rpc.example
FEE_AUTOMATIC=true
GAS_LIMIT=300000
```

Replace the wallet and RPC placeholders. Keep your private key and `.env` private.
`GAS_LIMIT=300000` is an example; choose a limit suitable for the mint. The tool
does not estimate gas during submission.

<h2 align="center">Usage</h2>

These commands work in Windows PowerShell 5.1, PowerShell 7, Command Prompt,
Linux Bash, and macOS Zsh or Bash. Run them from the `osnm-z` directory.
`uv run` uses the project's environment; manual activation is not required.

Check configuration, wallet signer loading, and RPC readiness:

```console
uv run --frozen osnm-z doctor
```

`doctor` does not submit a transaction. OpenSea wallet authentication and collection
eligibility are checked during mint setup.

Start an interactive mint:

```console
uv run --frozen osnm-z mint
```

1. Enter an OpenSea collection slug, collection or mint URL, or NFT contract address.
2. Choose one eligible phase using its displayed option number. If the collection
   has only one phase and it is selectable, the tool selects it automatically.
3. Enter the quantity, or press Enter for one NFT.
4. Keep the tool running while it prepares, waits for the selected phase if needed,
   submits, and checks the transaction receipt.

During scheduled waits, the terminal shows a live `Mint starts in 1h 23m 45s`
countdown to the selected phase's start. Preparation and early requests can finish
their waits before it reaches zero. Redirected logs record the remaining time once
per wait instead of printing repeated countdown updates.

Keep your system clock synchronized and avoid other transactions from the same
wallet while minting. If the result is uncertain or unconfirmed, check the printed
transaction hash before trying again; the tool does not automatically resend it.

Show command help:

```console
uv run --frozen osnm-z --help
```

Show version information:

```console
uv run --frozen osnm-z --version
```

<h2 align="center">Configuration</h2>

The tool reads its settings from `.env`. Unknown and duplicate keys are rejected.
[`.env.example`](.env.example) lists all supported settings.

<h3 align="center">Wallet and transaction settings</h3>

| Setting | Default | Description |
| --- | --- | --- |
| `WALLET_KEY` | Required | One private key, optionally prefixed with `0x`. |
| `RPC_URL` | Required | Endpoint for chain reads, submission, and receipt tracking. HTTPS is required except for HTTP loopback testing. |
| `FEE_AUTOMATIC` | Required | `true` for automatic fees or `false` for manual fees. |
| `GAS_LIMIT` | Required | Positive transaction gas limit; the example uses `300000`. |
| `MAX_FEE_PER_GAS_GWEI` | Manual mode only | Positive maximum fee per gas, in gwei. |
| `MAX_PRIORITY_FEE_PER_GAS_GWEI` | Manual mode only | Nonnegative priority fee, in gwei; must not exceed the maximum fee. |
| `RPC_REQUEST_TIMEOUT_MS` | `10000` | RPC request timeout; 100–120000 ms. |
| `PUBLIC_MINT_BROADCAST_OFFSET_MS` | `0` | Public submission lead time; 0–60000 ms. Positive values send early and can cause a revert if mined before the phase opens. |
| `PENDING_TIMEOUT_SECONDS` | `20` | Receipt-tracking timeout; 1–86400 seconds. |
| `RECEIPT_POLL_INTERVAL_MS` | `250` | Wait between receipt checks; 50–60000 ms. |

Automatic fees apply a 1.25× multiplier for an active phase and 2.5× for a scheduled
phase. Remove or comment out both manual fee settings when automatic fees are
enabled. For manual fees, set `FEE_AUTOMATIC=false` and supply both fee values.

<h3 align="center">OpenSea request settings</h3>

| Setting | Default | Description |
| --- | --- | --- |
| `OPENSEA_REQUEST_TIMEOUT_MS` | `10000` | General request timeout; 100–120000 ms. |
| `ELIGIBILITY_REQUEST_TIMEOUT_MS` | `5000` | Eligibility request timeout; 100–120000 ms. |
| `OPENSEA_ACTION_REQUEST_TIMEOUT_MS` | `3000` | Mint action request timeout; 100–120000 ms. |
| `OPENSEA_ATTEMPTS` | `6` | Request attempt limit; clamped to 6–10. |
| `OPENSEA_RETRY_INTERVAL_MS` | `250` | Request retry interval; 50–30000 ms. |
| `OPENSEA_CALLDATA_ATTEMPTS` | `15` | Private mint calldata attempt limit; clamped to 15–1000 and subject to timing and phase-expiry limits. |

`OPENSEA_ATTEMPTS` and `OPENSEA_RETRY_INTERVAL_MS` also govern RPC wallet preparation
and public settings recovery. Scheduled recovery uses its time window instead of
the attempt limit. Final wallet refresh and endpoint warm-up have separate retry
policies; these settings never cause a transaction to be resent.
