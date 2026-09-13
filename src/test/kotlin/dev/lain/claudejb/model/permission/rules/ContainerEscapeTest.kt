package dev.lain.claudejb.model.permission.rules

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ContainerEscapeTest : GuardProbe() {

    @Test
    fun `escaping onto the host is refused`() {
        listOf(
            "nsenter -t 1 -m -u -i -n -p bash",
            "nsenter --target 1 --mount --pid -- bash",
            "nsenter --mount=/proc/1/ns/mnt -- /bin/bash",
            "docker run -v /:/host alpine",
            "docker run --volume=/:/mnt ubuntu",
            "podman run --mount type=bind,source=/,target=/host img",
            "docker run --privileged alpine",
            "podman run --rm --privileged img",
            "docker run -v /var/run/docker.sock:/var/run/docker.sock img",
            "kubectl run x --overrides '{\"spec\":{\"hostPID\":true}}'",
            "kubectl run p --overrides '{\"spec\":{\"securityContext\":{\"privileged\":true}}}'",
            "docker run --cap-add=SYS_ADMIN alpine",
            "docker run --cap-add SYS_PTRACE img",
            "docker run --security-opt seccomp=unconfined img",
            "docker run --security-opt apparmor=unconfined img",
            "docker run --pid=host img",
            "docker run --network=host img",
            "docker run --ipc=host img",
            "docker run --userns=host img",
            "docker run -v /proc:/host/proc img",
            "docker run -v /sys:/host/sys img",
            "kubectl run x --overrides '{\"spec\":{\"hostNetwork\":true}}'",
            "kubectl run x --overrides '{\"spec\":{\"volumes\":[{\"hostPath\":{\"path\":\"/\"}}]}}'",
            "echo 0 > /sys/fs/cgroup/x/release_agent",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.CONTAINER_ESCAPE, rule(bash(it)), it)
        }
    }

    @Test
    fun `a privileged container or a dangerous capability is refused on every engine`() {
        listOf(
            "nerdctl run --privileged img",
            "kubectl run x --privileged --image=img",
            "oc debug node/n1 --as-root",
            "oc adm policy add-scc-to-user privileged -z default",
            "docker run --cap-add=NET_ADMIN img",
            "podman run --cap-add SYS_RAWIO img",
            "docker run --cap-add=SYS_CHROOT img",
            "docker run --cap-add DAC_OVERRIDE img",
            "docker run --cap-add=SYS_BOOT img",
            "docker run --cap-add BPF img",
            "docker run --cap-add=SETUID --cap-add=SETGID img",
            "docker run --security-opt label=disable img",
            "docker run --security-opt=no-new-privileges=false img",
            "kubectl run p --overrides '{\"spec\":{\"containers\":[{\"securityContext\":{\"allowPrivilegeEscalation\":true}}]}}'",
            "kubectl run p --overrides '{\"spec\":{\"containers\":[{\"securityContext\":{\"capabilities\":{\"add\":[\"SYS_ADMIN\"]}}}]}}'",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.CONTAINER_ESCAPE, rule(bash(it)), it)
        }
    }

    @Test
    fun `handing a raw device to a container is already the device rule's finding`() {
        listOf("docker run --device /dev/sda img", "podman run --device=/dev/mem img").forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.SYSTEM_DEVICE, rule(bash(it)), it)
        }
    }

    @Test
    fun `hardening flags and harmless devices are not escapes`() {
        listOf(
            "docker run --cap-drop ALL img",
            "docker run --security-opt no-new-privileges img",
            "docker run --security-opt=no-new-privileges=true img",
            "podman run --cap-add=NET_BIND_SERVICE img",
            "docker run --device /dev/dri img",
            "docker run --device=/dev/snd img",
            "kubectl run p --overrides '{\"spec\":{\"containers\":[{\"securityContext\":{\"allowPrivilegeEscalation\":false}}]}}'",
            "oc debug node/n1",
        ).forEach { assertNotEquals(SecurityRule.CONTAINER_ESCAPE, rule(bash(it)), it) }
    }

    @Test
    fun `ordinary container and namespace use is not touched`() {
        listOf(
            "docker run -v /home/me/proj:/app node npm test",
            "docker run -v /var/run/postgres:/data postgres",
            "nsenter -t 4321 -n ip addr",
            "docker build -t app .",
            "docker compose up -d",
            "kubectl get pods -n prod",
        ).forEach { assertNotEquals(SecurityRule.CONTAINER_ESCAPE, rule(bash(it)), it) }
    }
}
