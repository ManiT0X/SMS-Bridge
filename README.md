# SMS Bridge 📱⚡

**Use an Android phone as your own local SMS gateway.**

SMS Bridge lets your applications send SMS messages through a real Android phone and its SIM card, without relying on a third-party SMS service.

The main purpose of SMS Bridge is **automation**. For example, you can connect your own application to the SMS Bridge API and automatically send OTP verification codes when users sign up.

The **web dashboard is mainly for monitoring and managing the gateway**, rather than for manually sending messages.

## What can you do?

* 🔌 **Send SMS automatically through the API** from your own applications
* 🔐 **Send OTP verification codes** for user signup and account verification
* 📊 **Monitor the phone and gateway status**
* 📜 **View SMS logs and message history**
* 🔄 **Check failed messages and retry them**
* ⚙️ **Change gateway settings from your computer**
* 📱 **Monitor battery, Wi-Fi, SIM, and connection status**
* 🔢 **Use either SIM card on dual-SIM phones**
* 📩 **Send a single SMS manually from the dashboard when needed**
* 📁 **Export message history**

## How it works

SMS Bridge has two main parts:

### 📱 Android Phone

The Android phone acts as the SMS gateway. It uses its SIM card and mobile network to actually send the SMS.

### 💻 Web Dashboard

The dashboard runs on your computer and gives you a convenient way to:

* Check whether the phone and gateway are working
* View SMS logs and message history
* Monitor the phone's status
* Change settings
* Manually send an individual SMS when needed

You **do not need to keep touching or using the phone** to manage the gateway.

For automated messaging, your application communicates directly with the SMS Bridge **API**.

```text
Your Application
       │
       │ API
       ▼
SMS Bridge
       │
       │ Local Wi-Fi
       ▼
Android Phone
       │
       │ Mobile Network
       ▼
SMS Recipient
```

The dashboard is separate from the automated sending process. Think of it as the **control and monitoring panel**, while the API is what your applications use to send messages automatically.

## Example: Sending an OTP

A typical use case looks like this:

1. A user signs up for your application.
2. Your application generates an OTP.
3. Your application sends the OTP to the SMS Bridge through the API.
4. The Android phone sends the SMS using its SIM card.
5. The user receives the OTP.
6. You can use the dashboard to check the message status and view the logs.

This means your application can send hundreds of messages automatically without someone manually operating the dashboard or phone for every message.

## 🔒 Local & Private

SMS Bridge is designed to work locally.

Your computer communicates directly with the Android phone over your local Wi-Fi network instead of sending your messages through a cloud SMS provider.

The gateway also supports optional API-key authentication to help protect API access.

## 🚀 Getting Started

### 1. Set up the Android phone

Install the SMS Bridge Android app on your phone.

You can either build the app yourself or install the provided `sms-bridge-gateway.apk`.

Then:

1. Open the app.
2. Grant the required permissions.
3. Enable the SMS Gateway.
4. Note the IP address and port shown by the app.

### 2. Start the Dashboard

On your computer, install the required software and start the dashboard.

Once running, open the address shown in the terminal, usually:

```text
http://127.0.0.1:5000
```

### 3. Connect the Phone

Enter the IP address and port shown on the Android app into the dashboard settings.

Your computer and phone must be connected to the **same Wi-Fi network**.

Once connected, the dashboard can monitor and manage the phone.

## 📌 Current State

SMS Bridge is **functional but not production-ready**.

The core SMS gateway and API functionality works, but the project still has room for improvement in areas such as the user interface, reliability, usability, and additional features.

Contributions, fixes, and improvements are welcome.


## 📸 Screenshots

### Web Dashboard

<p align="center">
  <img src="https://github.com/user-attachments/assets/e9a247b1-c641-41cd-9f62-7a7cf7921632" width="48%" />
  <img src="https://github.com/user-attachments/assets/1f67e579-54b5-4728-9bba-34691c1543a7" width="48%" />
  
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/17745f3c-016c-4641-90b9-7caaded24580" width="70%" />
</p>

### Android Gateway

<p align="center">
  <img src="https://github.com/user-attachments/assets/72d41818-6c86-4004-9b3c-9231378c6089" width="30%" />
  <img src="https://github.com/user-attachments/assets/110a2db3-8d6a-4cff-8d4c-83e348281ed1" width="30%" />
</p>
