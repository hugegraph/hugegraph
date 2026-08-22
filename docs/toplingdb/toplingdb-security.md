# ToplingDB Security Hardening Guide

This document provides best practices for securing a ToplingDB deployment. It covers file permissions, network access control, firewall rules, and additional hardening measures to reduce the attack surface and ensure safe operation in production environments.

---

## 1. File Permissions

Restrict file permissions to prevent unauthorized access or modification of scripts and configuration files:

```bash
chmod 750 $HUGEGRAPH_HOME/bin/*.sh
chmod 640 $HUGEGRAPH_HOME/conf/graphs/*.yaml
chown -R hugegraph:hugegraph $HUGEGRAPH_HOME
```

- `750` ensures only the owner can execute scripts, while group members can read them.
- `640` ensures configuration files are readable by the owner and group, but not world-readable.
- Ownership should be assigned to a dedicated service account (e.g., `hugegraph`).

---

## 2. Network Access Control

Restrict network exposure by binding services to localhost or specific interfaces:

```yaml
# Localhost-only access
http:
  listening_ports: '127.0.0.1:2011'
```

- Avoid binding to `0.0.0.0` unless absolutely necessary.
- Use reverse proxies (e.g., Nginx) or VPN tunnels if remote access is required.

---

## 3. Firewall Rules

Use firewall rules to limit access to trusted IP ranges:

```bash
# Allow only specific subnet to access the Web Server
iptables -A INPUT -p tcp --dport 2011 \
  -s 192.168.1.0/24 -j ACCEPT
iptables -A INPUT -p tcp --dport 2011 -j DROP
```

- Replace `192.168.1.0/24` with your trusted network.
- Consider using `firewalld` or `ufw` for simplified management.
