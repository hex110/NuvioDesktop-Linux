# Arch Linux package

Each release also carries a prebuilt `nuvio-htpc-bin-*.pkg.tar.zst` (built by CI from
this PKGBUILD) and the PKGBUILD with that release's tag and checksum filled in.
Most people just want `sudo pacman -U` on the prebuilt package.

`PKGBUILD` repackages the `.deb` attached to each `vX.Y.Z-linuxN` release.

```bash
mkdir nuvio-htpc && cd nuvio-htpc
curl -O https://raw.githubusercontent.com/hex110/NuvioDesktop-Linux/linux/packaging/arch/PKGBUILD
makepkg -si
```

Installs to `/opt/nuvio-htpc`, provides the `nuvio-htpc` launcher, and keeps data
in `~/.config/nuviohtpc`, so it coexists with the stock Nuvio.

CI rewrites `_tag`, `pkgver` and the checksum for its own build; the committed values only
matter for building by hand. To bump them: edit `_tag` and `pkgver`, then run `updpkgsums`.
