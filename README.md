# web3j-evm [![Build Status](https://github.com/web3j/web3j-evm/actions/workflows/build.yml/badge.svg)](https://github.com/web3j/web3j-evm/actions/workflows/build.yml)

Web3j-evm is an embedded freestanding Ethereum EVM and ledger running
within a Java process, which can be used for unit and integration
testing your Web3j projects.

As everything is local and in-process, there is no need to start up
external Ethereum nodes, which helps with easy of use and performance.

Everything runs within the JVM process, including EVM bytecode, which
allows for easy debugging of Solidity smart contracts.

## Getting started

Often you'd use this together with the [web3j-unit](https://github.com/web3j/web3j-unit)
project, allowing you to run unit and integration tests without the need
to start an Ethereum node.

If you want to use this within our own project directly, you would need
the EVM dependency + a few external libraries. Have a look at
the [example project](https://github.com/web3j/web3j-evmexample) on how
to do this.

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation "org.web3j:core:4.13.0"
    implementation "org.web3j:web3j-evm:4.13.0"
}
```

Below is a simple demonstration of ETH transactions, contract deployment
and simple contract interactions. Using the ConsoleDebugTracer, we're
able to step through the EVM bytecode, inspect the stack and also see
where in the related solidity code we're currently at.

The demo also show to how get started with the `EmbeddedWeb3jService`
which is what you'd use when building your web3j instance.

This demo is available on the [example project](https://github.com/web3j/web3j-evmexample).

![](https://raw.githubusercontent.com/web3j/evm/master/resources/web3j-evm-demo.gif)
