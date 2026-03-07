# Karpfen Runtime - Documentation Index

Welcome to the Karpfen Runtime documentation.

## Start Here

1. [README.md](../README.md) - Project overview
2. [QUICK_REFERENCE.md](QUICK_REFERENCE.md) - Fast command and endpoint reference
3. [GETTING_STARTED.md](GETTING_STARTED.md) - End-to-end setup and workflow
4. [HTTP_API_ENDPOINTS.md](HTTP_API_ENDPOINTS.md) - Full API contract
5. [STATEMACHINE_EXECUTION_SEMANTICS.md](STATEMACHINE_EXECUTION_SEMANTICS.md) - Engine execution model and non-trivial behavior

## Guide Scope

### QUICK_REFERENCE.md
- Build and run commands
- Endpoint summary
- WebSocket message/auth format
- Troubleshooting checklist

### GETTING_STARTED.md
- Prerequisites and setup
- Configuration and architecture
- Complete example workflow
- Validation and troubleshooting

### HTTP_API_ENDPOINTS.md
- Endpoint-by-endpoint details
- Required parameters
- Error model
- HTTP and WebSocket examples

### STATEMACHINE_EXECUTION_SEMANTICS.md
- Tick loop phases (ENTRY → DO → transition check)
- State stack and hierarchical state handling
- Transition evaluation order and priority
- NOT LOOPING semantics and fallthrough
- Event consumption model and TTL
- Threading model and error handling
 
