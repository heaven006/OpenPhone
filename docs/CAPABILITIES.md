# Capability Model

OpenPhone capabilities are explicit grants. The assistant should never receive
undefined ambient authority just because it is a privileged component.

## Capability Catalog

```text
screen.read.visible
screen.capture
ui.target.resolve
input.perform
apps.launch
tasks.observe
notifications.read
notifications.act
clipboard.read
clipboard.write
share.content
files.read.scoped
contacts.read
calendar.read
calendar.write
messages.draft
messages.send
calls.place
settings.read
settings.write
background.run
network.use
account.access
```

## Risk Levels

Low-risk actions may run after task-level approval:

- Open app.
- Scroll.
- Navigate back/home.
- Summarize currently visible screen.

Medium-risk actions normally require contextual confirmation:

- Draft message.
- Modify calendar event.
- Change device setting.
- Download file.
- Read clipboard content.

High-risk actions always require explicit confirmation:

- Send message.
- Place call.
- Purchase item.
- Transfer money.
- Delete data.
- Share private content externally.

## Initial Config

The bootstrap policy file lives at:

```text
overlay/vendor/openphone/config/openphone_policy.json
```

This file is only declarative seed data. Real enforcement must be implemented
inside OpenPhone framework/system services.
