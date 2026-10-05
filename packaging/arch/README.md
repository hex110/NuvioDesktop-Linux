# Arch Linux package

`PKGBUILD` repackages the `.deb` attached to each `vX.Y.Z-linuxN` release.

```bash
mkdir nuvio-htpc && cd nuvio-htpc
curl -O https://raw.githubusercontent.com/hex110/NuvioDesktop-Linux/linux/packaging/arch/PKGBUILD
makepkg -si
```

Installs to `/opt/nuvio-htpc`, provides the `nuvio-htpc` launcher, and keeps data
in `~/.config/nuviohtpc`, so it coexists with the stock Nuvio.

Updating for a new release: bump `_tag` and `pkgver`, then run `updpkgsums`.
